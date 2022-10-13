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
package org.apache.asterix.translator.util;

import java.util.ArrayList;
import java.util.List;

import org.apache.asterix.common.config.DatasetConfig.IndexType;
import org.apache.asterix.common.exceptions.AsterixException;
import org.apache.asterix.common.exceptions.CompilationException;
import org.apache.asterix.common.exceptions.ErrorCode;
import org.apache.asterix.metadata.utils.KeyFieldTypeUtil;
import org.apache.asterix.om.types.ARecordType;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.om.types.BuiltinType;
import org.apache.asterix.om.types.IAType;
import org.apache.asterix.om.utils.RecordUtil;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.api.exceptions.SourceLocation;
import org.apache.hyracks.util.LogRedactionUtil;

/**
 * A util that can verify if a filter field, a list of partitioning expressions,
 * or a list of key fields are valid in a record type.
 */
public class ValidateUtil {
    private ValidateUtil() {
    }

    /**
     * Validates the field that will be used as filter for the components of an LSM index.
     *
     * @param dataset
     *            the dataset
     * @param recordType
     *            the record type
     * @param filterField
     *            the full name of the field
     * @param sourceLoc
     * @throws AlgebricksException
     *             if field is not found in record.
     *             if field type can't be a filter type.
     *             if field type is nullable.
     */
    public static void validateFilterField(ARecordType recordType, List<String> filterField, SourceLocation sourceLoc)
            throws AlgebricksException {
        IAType fieldType = recordType.getSubFieldType(filterField);
        if (fieldType == null) {
            throw new CompilationException(ErrorCode.COMPILATION_FIELD_NOT_FOUND, sourceLoc,
                    LogRedactionUtil.userData(RecordUtil.toFullyQualifiedName(filterField)));
        }
        switch (fieldType.getTypeTag()) {
            case TINYINT:
            case SMALLINT:
            case INTEGER:
            case BIGINT:
            case FLOAT:
            case DOUBLE:
            case STRING:
            case BINARY:
            case DATE:
            case TIME:
            case DATETIME:
            case UUID:
            case YEARMONTHDURATION:
            case DAYTIMEDURATION:
                break;
            case UNION:
                throw new CompilationException(ErrorCode.COMPILATION_FILTER_CANNOT_BE_NULLABLE,
                        LogRedactionUtil.userData(RecordUtil.toFullyQualifiedName(filterField)));
            default:
                throw new CompilationException(ErrorCode.COMPILATION_ILLEGAL_FILTER_TYPE,
                        fieldType.getTypeTag().name());
        }
    }

    /**
     * Validates the partitioning expression that will be used to partition a dataset and returns expression type.
     *
     * @param recType
     *            the record type
     * @param metaRecType
     *            the meta record type
     * @param partitioningExprs
     *            a list of partitioning expressions that will be validated
     * @param keySourceIndicators
     *            the key sources (record vs. meta)
     * @param autogenerated
     *            true if auto generated, false otherwise
     * @param sourceLoc
     * @return a list of partitioning expressions types
     * @throws AlgebricksException
     *             if composite key is autogenerated.
     *             if autogenerated and of a type that can't be autogenerated.
     *             if a field could not be found in its record type.
     *             if partitioning key is nullable.
     *             if the field type can't be a primary key.
     */
    public static List<IAType> validatePartitioningExpressions(ARecordType recType, ARecordType metaRecType,
            List<List<String>> partitioningExprs, List<Integer> keySourceIndicators, boolean autogenerated,
            SourceLocation sourceLoc) throws AlgebricksException {
        List<IAType> partitioningExprTypes = new ArrayList<>(partitioningExprs.size());
        if (autogenerated) {
            if (partitioningExprs.size() > 1) {
                throw new CompilationException(ErrorCode.COMPILATION_CANNOT_AUTOGENERATE_COMPOSITE_PRIMARY_KEY,
                        sourceLoc);
            }
            List<String> fieldName = partitioningExprs.get(0);
            IAType fieldType = recType.getSubFieldType(fieldName);
            if (fieldType == null) {
                String unTypeField = fieldName.get(0) == null ? "" : fieldName.get(0);
                throw new CompilationException(ErrorCode.COMPILATION_FIELD_NOT_FOUND, sourceLoc,
                        LogRedactionUtil.userData(unTypeField));
            }
            partitioningExprTypes.add(fieldType);
            ATypeTag pkTypeTag = fieldType.getTypeTag();
            if (pkTypeTag != ATypeTag.UUID) {
                throw new CompilationException(ErrorCode.COMPILATION_ILLEGAL_AUTOGENERATED_TYPE, sourceLoc,
                        pkTypeTag.name(), ATypeTag.UUID.name());
            }
        } else {
            partitioningExprTypes =
                    KeyFieldTypeUtil.getKeyTypes(recType, metaRecType, partitioningExprs, keySourceIndicators);
            for (int i = 0; i < partitioningExprs.size(); i++) {
                List<String> partitioningExpr = partitioningExprs.get(i);
                IAType fieldType = partitioningExprTypes.get(i);
                if (fieldType == null) {
                    throw new CompilationException(ErrorCode.COMPILATION_FIELD_NOT_FOUND, sourceLoc,
                            LogRedactionUtil.userData(RecordUtil.toFullyQualifiedName(partitioningExpr)));
                }
                boolean nullable = KeyFieldTypeUtil.chooseSource(keySourceIndicators, i, recType, metaRecType)
                        .isSubFieldNullable(partitioningExpr);
                if (nullable) {
                    // key field is nullable
                    throw new CompilationException(ErrorCode.COMPILATION_PRIMARY_KEY_CANNOT_BE_NULLABLE, sourceLoc,
                            LogRedactionUtil.userData(RecordUtil.toFullyQualifiedName(partitioningExpr)));
                }
                switch (fieldType.getTypeTag()) {
                    case TINYINT:
                    case SMALLINT:
                    case INTEGER:
                    case BIGINT:
                    case FLOAT:
                    case DOUBLE:
                    case STRING:
                    case BINARY:
                    case DATE:
                    case TIME:
                    case UUID:
                    case DATETIME:
                    case YEARMONTHDURATION:
                    case DAYTIMEDURATION:
                        break;
                    case UNION:
                        throw new CompilationException(ErrorCode.COMPILATION_PRIMARY_KEY_CANNOT_BE_NULLABLE, sourceLoc,
                                LogRedactionUtil.userData(RecordUtil.toFullyQualifiedName(partitioningExpr)));
                    default:
                        throw new CompilationException(ErrorCode.COMPILATION_ILLEGAL_PRIMARY_KEY_TYPE, sourceLoc,
                                fieldType.getTypeTag());
                }
            }
        }
        return partitioningExprTypes;
    }

    /**
     * Validates the key fields that will be used as keys of an index.
     *
     * @param recType
     *         the record type
     * @param keyFieldNames
     *         a map of key fields that will be validated
     * @param keyFieldTypes
     *         a map of key types (if provided) that will be validated
     * @param indexType
     *         the type of the index that its key fields is being validated
     * @throws AlgebricksException
     */
    public static void validateKeyFields(ARecordType recType, ARecordType metaRecType, List<List<String>> keyFieldNames,
            List<Integer> keySourceIndicators, List<IAType> keyFieldTypes, IndexType indexType,
            SourceLocation sourceLoc) throws AlgebricksException {
        List<IAType> fieldTypes =
                KeyFieldTypeUtil.getKeyTypes(recType, metaRecType, keyFieldNames, keySourceIndicators);
        int pos = 0;
        boolean openFieldCompositeIdx = false;
        for (IAType fieldType : fieldTypes) {
            List<String> fieldName = keyFieldNames.get(pos);
            if (fieldType == null) {
                fieldType = keyFieldTypes.get(pos);
                if (keyFieldTypes.get(pos) == BuiltinType.AMISSING) {
                    throw new CompilationException(ErrorCode.COMPILATION_ERROR, sourceLoc,
                            "A field with this name  \""
                                    + LogRedactionUtil.userData(RecordUtil.toFullyQualifiedName(fieldName))
                                    + "\" could not be found.");
                }
            } else if (openFieldCompositeIdx) {
                throw new CompilationException(ErrorCode.COMPILATION_ERROR, sourceLoc,
                        "A closed field \"" + LogRedactionUtil.userData(RecordUtil.toFullyQualifiedName(fieldName))
                                + "\" could be only in a prefix part of the composite index, containing opened field.");
            }
            if (keyFieldTypes.get(pos) != BuiltinType.AMISSING
                    && fieldType.getTypeTag() != keyFieldTypes.get(pos).getTypeTag()) {
                throw new CompilationException(ErrorCode.COMPILATION_ERROR, sourceLoc,
                        "A field \"" + LogRedactionUtil.userData(RecordUtil.toFullyQualifiedName(fieldName)) + "\" is "
                                + "already defined with the type \"" + fieldType + "\"");
            }
            switch (indexType) {
                case BTREE:
                    switch (fieldType.getTypeTag()) {
                        case TINYINT:
                        case SMALLINT:
                        case INTEGER:
                        case BIGINT:
                        case FLOAT:
                        case DOUBLE:
                        case STRING:
                        case BINARY:
                        case DATE:
                        case TIME:
                        case DATETIME:
                        case UNION:
                        case UUID:
                        case YEARMONTHDURATION:
                        case DAYTIMEDURATION:
                            break;
                        default:
                            throw new CompilationException(ErrorCode.COMPILATION_ERROR, sourceLoc,
                                    "The field '"
                                            + LogRedactionUtil.userData(RecordUtil.toFullyQualifiedName(fieldName))
                                            + "' which is of type " + fieldType.getTypeTag()
                                            + " cannot be indexed using the BTree index.");
                    }
                    break;
                case RTREE:
                    switch (fieldType.getTypeTag()) {
                        case POINT:
                        case LINE:
                        case RECTANGLE:
                        case CIRCLE:
                        case POLYGON:
                        case UNION:
                            break;
                        default:
                            throw new CompilationException(ErrorCode.COMPILATION_ERROR, sourceLoc,
                                    "The field '"
                                            + LogRedactionUtil.userData(RecordUtil.toFullyQualifiedName(fieldName))
                                            + "' which is of type " + fieldType.getTypeTag()
                                            + " cannot be indexed using the RTree index.");
                    }
                    break;
                case LENGTH_PARTITIONED_NGRAM_INVIX:
                    switch (fieldType.getTypeTag()) {
                        case STRING:
                        case UNION:
                            break;
                        default:
                            throw new CompilationException(ErrorCode.COMPILATION_ERROR, sourceLoc,
                                    "The field '"
                                            + LogRedactionUtil.userData(RecordUtil.toFullyQualifiedName(fieldName))
                                            + "' which is of type " + fieldType.getTypeTag()
                                            + " cannot be indexed using the Length Partitioned N-Gram index.");
                    }
                    break;
                case LENGTH_PARTITIONED_WORD_INVIX:
                    switch (fieldType.getTypeTag()) {
                        case STRING:
                        case MULTISET:
                        case ARRAY:
                        case UNION:
                            break;
                        default:
                            throw new CompilationException(ErrorCode.COMPILATION_ERROR, sourceLoc,
                                    "The field '"
                                            + LogRedactionUtil.userData(RecordUtil.toFullyQualifiedName(fieldName))
                                            + "' which is of type " + fieldType.getTypeTag()
                                            + " cannot be indexed using the Length Partitioned Keyword index.");
                    }
                    break;
                case SINGLE_PARTITION_NGRAM_INVIX:
                    switch (fieldType.getTypeTag()) {
                        case STRING:
                        case UNION:
                            break;
                        default:
                            throw new CompilationException(ErrorCode.COMPILATION_ERROR, sourceLoc,
                                    "The field '"
                                            + LogRedactionUtil.userData(RecordUtil.toFullyQualifiedName(fieldName))
                                            + "' which is of type " + fieldType.getTypeTag()
                                            + " cannot be indexed using the N-Gram index.");
                    }
                    break;
                case SINGLE_PARTITION_WORD_INVIX:
                    switch (fieldType.getTypeTag()) {
                        case STRING:
                        case MULTISET:
                        case ARRAY:
                        case UNION:
                            break;
                        default:
                            throw new CompilationException(ErrorCode.COMPILATION_ERROR, sourceLoc,
                                    "The field '"
                                            + LogRedactionUtil.userData(RecordUtil.toFullyQualifiedName(fieldName))
                                            + "' which is of type " + fieldType.getTypeTag()
                                            + " cannot be indexed using the Keyword index.");
                    }
                    break;
                default:
                    throw new AsterixException("Invalid index type: " + indexType + ".");
            }
            pos++;
        }
    }

}
