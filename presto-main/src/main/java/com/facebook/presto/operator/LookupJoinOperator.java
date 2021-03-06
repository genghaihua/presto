/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator;

import com.facebook.presto.operator.LookupJoinOperators.JoinType;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.PageBuilder;
import com.facebook.presto.spi.type.Type;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.Closeable;
import java.util.List;

import static com.facebook.presto.util.MoreFutures.tryGetUnchecked;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class LookupJoinOperator
        implements Operator, Closeable
{
    private final ListenableFuture<LookupSource> lookupSourceFuture;

    private final OperatorContext operatorContext;
    private final JoinProbeFactory joinProbeFactory;
    private final JoinType joinType;
    private final List<Type> types;
    private final List<Type> probeTypes;
    private final PageBuilder pageBuilder;

    private LookupSource lookupSource;
    private JoinProbe probe;

    private boolean finishing;
    private long joinPosition = -1;

    private int buildSideNextUnvisitedKeyId = -1;
    private long buildSideOuterJoinPosition = -1;

    public LookupJoinOperator(
            OperatorContext operatorContext,
            LookupSourceSupplier lookupSourceSupplier,
            List<Type> probeTypes,
            JoinType joinType,
            JoinProbeFactory joinProbeFactory)
    {
        this.operatorContext = checkNotNull(operatorContext, "operatorContext is null");

        // todo pass in desired projection
        checkNotNull(lookupSourceSupplier, "lookupSourceSupplier is null");
        checkNotNull(probeTypes, "probeTypes is null");

        this.lookupSourceFuture = lookupSourceSupplier.getLookupSource(operatorContext);
        this.joinProbeFactory = joinProbeFactory;
        this.joinType = joinType;

        this.types = ImmutableList.<Type>builder()
                .addAll(probeTypes)
                .addAll(lookupSourceSupplier.getTypes())
                .build();
        this.probeTypes = probeTypes;
        this.pageBuilder = new PageBuilder(types);
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public List<Type> getTypes()
    {
        return types;
    }

    @Override
    public void finish()
    {
        finishing = true;
    }

    @Override
    public boolean isFinished()
    {
        boolean finished = finishing && probe == null && pageBuilder.isEmpty() &&
                (joinType != JoinType.PROBE_LOOKUP_OUTER || buildSideNextUnvisitedKeyId == LookupSource.NO_MORE_BUILD_SIDE_OUTER_JOIN_POSITIONS);

        // if finished drop references so memory is freed early
        if (finished) {
            if (lookupSource != null) {
                lookupSource.close();
                lookupSource = null;
            }
            probe = null;
            pageBuilder.reset();
        }
        return finished;
    }

    @Override
    public ListenableFuture<?> isBlocked()
    {
        return lookupSourceFuture;
    }

    @Override
    public boolean needsInput()
    {
        if (finishing) {
            return false;
        }

        if (lookupSource == null) {
            lookupSource = tryGetUnchecked(lookupSourceFuture);
        }
        return lookupSource != null && probe == null;
    }

    @Override
    public void addInput(Page page)
    {
        checkNotNull(page, "page is null");
        checkState(!finishing, "Operator is finishing");
        checkState(lookupSource != null, "Lookup source has not been built yet");
        checkState(probe == null, "Current page has not been completely processed yet");

        // create probe
        probe = joinProbeFactory.createJoinProbe(lookupSource, page);

        // initialize to invalid join position to force output code to advance the cursors
        joinPosition = -1;
    }

    @Override
    public Page getOutput()
    {
        // If needsInput was never called, lookupSource has not been initialized so far.
        if (lookupSource == null) {
            lookupSource = tryGetUnchecked(lookupSourceFuture);
        }

        // join probe page with the lookup source
        if (probe != null) {
            while (joinCurrentPosition()) {
                if (!advanceProbePosition()) {
                    break;
                }
                if (!outerJoinCurrentPosition()) {
                    break;
                }
            }
        }

        if (joinType == JoinType.PROBE_LOOKUP_OUTER && finishing && probe == null) {
            while (buildSideOuterJoinCurrentPosition()) {
                if (!advanceBuildSideOuterJoinPosition()) {
                    break;
                }
            }
        }

        // only flush full pages unless we are done
        if (pageBuilder.isFull() || (finishing && !pageBuilder.isEmpty() && probe == null)) {
            Page page = pageBuilder.build();
            pageBuilder.reset();
            return page;
        }

        return null;
    }

    @Override
    public void close()
    {
        if (lookupSource != null) {
            lookupSource.close();
            lookupSource = null;
        }
    }

    private boolean joinCurrentPosition()
    {
        // while we have a position to join against...
        while (joinPosition >= 0) {
            pageBuilder.declarePosition();

            // write probe columns
            probe.appendTo(pageBuilder);

            // write build columns
            lookupSource.appendTo(joinPosition, pageBuilder, probe.getChannelCount());

            // get next join position for this row
            joinPosition = lookupSource.getNextJoinPosition(joinPosition);
            if (pageBuilder.isFull()) {
                return false;
            }
        }
        return true;
    }

    private boolean advanceProbePosition()
    {
        if (!probe.advanceNextPosition()) {
            probe = null;
            return false;
        }

        // update join position
        joinPosition = probe.getCurrentJoinPosition();
        return true;
    }

    private boolean outerJoinCurrentPosition()
    {
        if (joinType != JoinType.INNER && joinPosition < 0) {
            // write probe columns
            pageBuilder.declarePosition();
            probe.appendTo(pageBuilder);

            // write nulls into build columns
            int outputIndex = probe.getChannelCount();
            for (int buildChannel = 0; buildChannel < lookupSource.getChannelCount(); buildChannel++) {
                pageBuilder.getBlockBuilder(outputIndex).appendNull();
                outputIndex++;
            }
            if (pageBuilder.isFull()) {
                return false;
            }
        }
        return true;
    }

    private boolean advanceBuildSideOuterJoinPosition()
    {
        buildSideNextUnvisitedKeyId = lookupSource.getNextUnvisitedKeyId(buildSideNextUnvisitedKeyId);
        if (buildSideNextUnvisitedKeyId == LookupSource.NO_MORE_BUILD_SIDE_OUTER_JOIN_POSITIONS) {
            buildSideOuterJoinPosition = -1;
            return false;
        }
        buildSideOuterJoinPosition = lookupSource.getJoinPositionForKeyId(buildSideNextUnvisitedKeyId);
        return true;
    }

    private boolean buildSideOuterJoinCurrentPosition()
    {
        // while we have a position to join against...
        while (buildSideOuterJoinPosition >= 0) {
            pageBuilder.declarePosition();

            // write nulls into probe columns
            for (int probeChannel = 0; probeChannel < probeTypes.size(); probeChannel++) {
                pageBuilder.getBlockBuilder(probeChannel).appendNull();
            }

            // write build columns
            lookupSource.appendTo(buildSideOuterJoinPosition, pageBuilder, probeTypes.size());

            // get next join position for this row
            buildSideOuterJoinPosition = lookupSource.getNextJoinPosition(buildSideOuterJoinPosition);
            if (pageBuilder.isFull()) {
                return false;
            }
        }
        return true;
    }
}
