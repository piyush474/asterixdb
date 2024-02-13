/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.external.input.record.reader.stream;

import static org.apache.asterix.external.util.ExternalDataConstants.EMPTY_STRING;
import static org.apache.asterix.external.util.ExternalDataConstants.KEY_REDACT_WARNINGS;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.asterix.external.api.AsterixInputStream;
import org.apache.asterix.external.api.IRecordReader;
import org.apache.asterix.external.util.ExternalDataUtils;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.exceptions.HyracksDataException;

public abstract class AbstractStreamRecordReader<T> implements IRecordReader<T> {
    private Supplier<String> dataSourceName = EMPTY_STRING;
    private Supplier<String> previousDataSourceName = EMPTY_STRING;

    protected final void setSuppliers(Map<String, String> config, Supplier<String> dataSourceName,
            Supplier<String> previousDataSourceName) {
        if (!ExternalDataUtils.isTrue(config, KEY_REDACT_WARNINGS)) {
            this.dataSourceName = dataSourceName;
            this.previousDataSourceName = previousDataSourceName;
        }
    }

    @Override
    public final Supplier<String> getDataSourceName() {
        return dataSourceName;
    }

    protected final String getPreviousStreamName() {
        return previousDataSourceName.get();
    }

    public abstract List<String> getRecordReaderFormats();

    public abstract void configure(IHyracksTaskContext ctx, AsterixInputStream inputStream, Map<String, String> config)
            throws HyracksDataException;
}
