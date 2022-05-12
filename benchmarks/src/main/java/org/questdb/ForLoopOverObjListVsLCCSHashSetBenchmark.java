/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package org.questdb;

import io.questdb.std.Chars;
import io.questdb.std.LowerCaseCharSequenceHashSet;
import io.questdb.std.ObjList;
import io.questdb.std.Rnd;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ForLoopOverObjListVsLCCSHashSetBenchmark {

    private static final int NUMBER_OF_LOOKUPS = 10;

    private static final LowerCaseCharSequenceHashSet COLUMN_NAMES_HS = new LowerCaseCharSequenceHashSet();

    private static final ObjList<CharSequence> COLUMN_NAMES_OL = new ObjList<>();

    static {
        COLUMN_NAMES_OL.add("我的泰勒很有钱");
        COLUMN_NAMES_OL.add("мій кравець багатий");
        for (int i = 1; i < 5; i++) {
            COLUMN_NAMES_OL.add("Χρόνια_και_χρόνια_" + i);
        }
        for (int i = 1; i < 5; i++) {
            COLUMN_NAMES_OL.add("BRAZIL_" + i);
        }
        for (int i = 0, limit = COLUMN_NAMES_OL.size(); i < limit; i++) {
            COLUMN_NAMES_HS.add(COLUMN_NAMES_OL.get(i));
        }
    }

    @Benchmark
    public static void containsColumnByNameForOverObjList() {
        final Rnd rnd = new Rnd();
        final int limit = COLUMN_NAMES_OL.size();
        for (int j = 0, m = NUMBER_OF_LOOKUPS; j < m; j++) {
            final CharSequence target = COLUMN_NAMES_OL.get(rnd.nextInt(limit));
            if (!containsColumnByName(COLUMN_NAMES_OL, target)) {
                throw new AssertionError();
            }
        }
    }

    @Benchmark
    public static void containsColumnByNameHashSet() {
        final Rnd rnd = new Rnd();
        final int limit = COLUMN_NAMES_OL.size();
        for (int j = 0, m = NUMBER_OF_LOOKUPS; j < m; j++) {
            final CharSequence target = COLUMN_NAMES_OL.get(rnd.nextInt(limit));
            if (!COLUMN_NAMES_HS.contains(target)) {
                throw new AssertionError();
            }
        }
    }

    private static boolean containsColumnByName(ObjList<CharSequence> columns, CharSequence colName) {
        for (int i = 0, limit = columns.size(); i < limit; i++) {
            if (Chars.equalsIgnoreCase(columns.getQuick(i), colName)) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        new Runner(
                new OptionsBuilder()
                        .include(ForLoopOverObjListVsLCCSHashSetBenchmark.class.getSimpleName())
                        .warmupIterations(2)
                        .measurementIterations(3)
                        .forks(1)
                        .build()
        ).run();
    }
}