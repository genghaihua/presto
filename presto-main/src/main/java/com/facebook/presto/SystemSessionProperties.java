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
package com.facebook.presto;

import io.airlift.units.DataSize;

public final class SystemSessionProperties
{
    public static final String BIG_QUERY = "experimental_big_query";
    private static final String OPTIMIZE_HASH_GENERATION = "optimize_hash_generation";
    private static final String DISTRIBUTED_JOIN = "distributed_join";
    private static final String TASK_WRITER_COUNT = "task_writer_count";
    private static final String TASK_MAX_MEMORY = "task_max_memory";

    private SystemSessionProperties() {}

    public static boolean isBigQueryEnabled(Session session, boolean defaultValue)
    {
        return isEnabled(BIG_QUERY, session, defaultValue);
    }

    private static boolean isEnabled(String propertyName, Session session, boolean defaultValue)
    {
        String enabled = session.getSystemProperties().get(propertyName);
        if (enabled == null) {
            return defaultValue;
        }

        return Boolean.valueOf(enabled);
    }

    private static int getNumber(String propertyName, Session session, int defaultValue)
    {
        String count = session.getSystemProperties().get(propertyName);
        if (count != null) {
            try {
                return Integer.valueOf(count);
            }
            catch (NumberFormatException ignored) {
            }
        }

        return defaultValue;
    }

    private static DataSize getDataSize(String propertyName, Session session, DataSize defaultValue)
    {
        String size = session.getSystemProperties().get(propertyName);
        if (size != null) {
            try {
                return DataSize.valueOf(size);
            }
            catch (NumberFormatException ignored) {
            }
        }

        return defaultValue;
    }

    public static boolean isOptimizeHashGenerationEnabled(Session session, boolean defaultValue)
    {
        return isEnabled(OPTIMIZE_HASH_GENERATION, session, defaultValue);
    }

    public static boolean isDistributedJoinEnabled(Session session, boolean defaultValue)
    {
        return isEnabled(DISTRIBUTED_JOIN, session, defaultValue);
    }

    public static int getTaskWriterCount(Session session, int defaultValue)
    {
        return getNumber(TASK_WRITER_COUNT, session, defaultValue);
    }

    public static DataSize getTaskMaxMemory(Session session, DataSize defaultValue)
    {
        return getDataSize(TASK_MAX_MEMORY, session, defaultValue);
    }
}
