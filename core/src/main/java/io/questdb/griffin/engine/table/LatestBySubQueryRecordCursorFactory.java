/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
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

package io.questdb.griffin.engine.table;

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.ColumnType;
import io.questdb.cairo.TableUtils;
import io.questdb.cairo.sql.*;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.engine.StrTypeCaster;
import io.questdb.griffin.engine.SymbolTypeCaster;
import io.questdb.griffin.engine.TypeCaster;
import io.questdb.std.IntHashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class LatestBySubQueryRecordCursorFactory extends AbstractTreeSetRecordCursorFactory {
    private final int columnIndex;
    // this instance is shared between factory and cursor
    // factory will be resolving symbols for cursor and if successful
    // symbol keys will be added to this hash set
    private final IntHashSet symbolKeys = new IntHashSet();
    private final RecordCursorFactory recordCursorFactory;
    private final TypeCaster typeCaster;
    private final Function filter;

    public LatestBySubQueryRecordCursorFactory(
            @NotNull CairoConfiguration configuration,
            @NotNull RecordMetadata metadata,
            @NotNull DataFrameCursorFactory dataFrameCursorFactory,
            int columnIndex,
            @NotNull RecordCursorFactory recordCursorFactory,
            @Nullable Function filter,
            boolean indexed,
            int firstColumnType) {
        super(metadata, dataFrameCursorFactory, configuration);
        if (indexed) {
            if (filter != null) {
                this.cursor = new LatestByValuesIndexedFilteredRecordCursor(columnIndex, rows, symbolKeys, filter);
            } else {
                this.cursor = new LatestByValuesIndexedRecordCursor(columnIndex, symbolKeys, rows);
            }
        } else {
            if (filter != null) {
                this.cursor = new LatestByValuesFilteredRecordCursor(columnIndex, rows, symbolKeys, filter);
            } else {
                this.cursor = new LatestByValuesRecordCursor(columnIndex, rows, symbolKeys);
            }
        }
        this.columnIndex = columnIndex;
        this.recordCursorFactory = recordCursorFactory;
        if (firstColumnType == ColumnType.STRING) {
            typeCaster = StrTypeCaster.INSTANCE;
        } else {
            typeCaster = SymbolTypeCaster.INSTANCE;
        }
        this.filter = filter;
    }

    @Override
    public void close() {
        super.close();
        recordCursorFactory.close();
        if (filter != null) {
            filter.close();
        }
    }

    @Override
    protected AbstractDataFrameRecordCursor getCursorInstance(
            DataFrameCursor dataFrameCursor,
            SqlExecutionContext executionContext
    ) {
        SymbolTable symbolTable = dataFrameCursor.getSymbolTable(columnIndex);
        symbolKeys.clear();
        try (RecordCursor cursor = recordCursorFactory.getCursor(executionContext)) {
            final Record record = cursor.getRecord();
            while (cursor.hasNext()) {
                int symbolKey = symbolTable.getQuick(typeCaster.getValue(record, 0));
                if (symbolKey != SymbolTable.VALUE_NOT_FOUND) {
                    symbolKeys.add(TableUtils.toIndexKey(symbolKey));
                }
            }
        }

        if (filter != null) {
            AbstractDataFrameRecordCursor cursor = super.getCursorInstance(dataFrameCursor, executionContext);
            filter.init(cursor, executionContext);
            return cursor;
        }
        return super.getCursorInstance(dataFrameCursor, executionContext);
    }

    @Override
    public boolean isRandomAccessCursor() {
        return true;
    }
}
