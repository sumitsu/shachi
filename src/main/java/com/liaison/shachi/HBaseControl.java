/*
 * Copyright © 2016 Liaison Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.liaison.shachi;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.liaison.javabasics.commons.Util;
import com.liaison.javabasics.logging.JitLog;
import com.liaison.javabasics.serialization.DefensiveCopyStrategy;
import com.liaison.shachi.api.request.OperationController;
import com.liaison.shachi.api.request.frozen.ColSpecWriteFrozen;
import com.liaison.shachi.api.request.frozen.LongValueSpecFrozen;
import com.liaison.shachi.api.request.frozen.ReadOpSpecFrozen;
import com.liaison.shachi.api.request.impl.ColSpecRead;
import com.liaison.shachi.api.request.impl.CondSpec;
import com.liaison.shachi.api.request.impl.LongValueSpec;
import com.liaison.shachi.api.request.impl.OperationControllerDefault;
import com.liaison.shachi.api.request.impl.OperationSpec;
import com.liaison.shachi.api.request.impl.ReadOpSpecDefault;
import com.liaison.shachi.api.request.impl.RowSpec;
import com.liaison.shachi.api.request.impl.WriteOpSpecDefault;
import com.liaison.shachi.api.response.OpResultSet;
import com.liaison.shachi.context.HBaseContext;
import com.liaison.shachi.dto.ApplicableVersion;
import com.liaison.shachi.dto.FamilyQualifierPair;
import com.liaison.shachi.dto.GetColumnGrouping;
import com.liaison.shachi.dto.NullableValue;
import com.liaison.shachi.dto.RowKey;
import com.liaison.shachi.dto.RowRef;
import com.liaison.shachi.exception.HBaseException;
import com.liaison.shachi.exception.HBaseMultiColumnException;
import com.liaison.shachi.exception.HBaseRuntimeException;
import com.liaison.shachi.exception.HBaseTableRowException;
import com.liaison.shachi.model.ColumnRange;
import com.liaison.shachi.model.FamilyHB;
import com.liaison.shachi.model.Name;
import com.liaison.shachi.model.QualHB;
import com.liaison.shachi.model.QualModel;
import com.liaison.shachi.model.VersioningModel;
import com.liaison.shachi.resmgr.HBaseResourceManager;
import com.liaison.shachi.resmgr.res.ManagedTable;
import com.liaison.shachi.util.HBaseUtil;
import com.liaison.shachi.util.ReadUtils;
import com.liaison.shachi.util.SpecUtil;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.QualifierFilter;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * HBaseControl is the main kernel of functionality for the HBase Client, and as the default
 * implementation of HBaseStart, is the primary starting point for use of the fluent API.
 * 
 * Per the contract for {@link HBaseStart#begin()}, {@link HBaseControl#begin()} starts the API
 * operation-specification generation process by which clients specify HBase read/write operations
 * to execute. The {@link OperationController} created when spec-generation begins is granted
 * access to the private, singleton instance of the internal class {@link HBaseDelegate}, which is
 * responsible for interpreting and executing the spec once the {@link OperationController}
 * indicates that spec-generation is complete.
 * 
 * @author Branden Smith; Liaison Technologies, Inc.
 */
public class HBaseControl implements HBaseStart<OpResultSet>, Closeable {
    
    // ||========================================================================================||
    // ||    INNER CLASSES (INSTANCE)                                                            ||
    // ||----------------------------------------------------------------------------------------||

    @FunctionalInterface
    public interface HBaseCheckAndMutate<M extends Mutation> {
        boolean checkAndMutate(byte[] condRow, byte[] condFamily, byte[] condQual, byte[] condValue, M operation) throws IOException;
    }
    @FunctionalInterface
    public interface HBaseMutate<M extends Mutation> {
        void mutate(M operation) throws IOException;
    }

    /**
     * Internal class implementation owned and controlled by an {@link HBaseControl} instance, and
     * responsible for interpreting and executing the HBase operation(s) indicated by an operation
     * specification. HBaseDelegate maintains no internal state of its own, and only references the
     * {@link HBaseContext} object governing the configuration of its controlling
     * {@link HBaseControl}, so it should be safe for concurrent use by multiple threads;
     * accordingly, a single instance of it is maintained within an HBaseControl object, and a
     * reference to it is parceled out to {@link OperationController} whenever an operation starts
     * via {@link HBaseControl#begin()}.
     * 
     * @author Branden Smith; Liaison Technologies, Inc.
     */
    public final class HBaseDelegate {

        /**
         * TODO
         * @param colRangeSet
         * @param verModel
         * @param colFam
         * @param colQual
         * @param multiVersion
         */
        private void buildVersioningDerivedQualifiers(final Set<ColumnRange> colRangeSet, final VersioningModel verModel, final FamilyHB colFam, final QualHB colQual, final LongValueSpecFrozen multiVersion) {
            final String logMethodName;

            logMethodName =
                LOG.enter(()->"buildVersioningDerivedQualifiers(colRangeSet=",
                          ()->colRangeSet,
                          ()->",scheme=",
                          ()->verModel,
                          ()->",ver=",
                          ()->multiVersion,
                          ()->")");

            /*
             * For the versioning scheme specified by the model, add a qualifier to the read spec,
             * modified to accommodate the versioning scheme, if necessary. Create a new qualifier
             * range representing the core qualifier with version info appended per the range of
             * version specification.
             */
            colRangeSet.add(ColumnRange.from(colFam, colQual, verModel, multiVersion));
            LOG.trace(logMethodName, ()->"colRangeSet=", ()->colRangeSet);
            LOG.leave(logMethodName);
        }

        /**
         * TODO
         * @param fqpSet
         * @param verModel
         * @param colFam
         * @param colQual
         * @param singleVersion
         */
        private void buildVersioningDerivedQualifiers(final Set<FamilyQualifierPair> fqpSet, final VersioningModel verModel, final FamilyHB colFam, final QualHB colQual, final Long singleVersion) {
            final String logMethodName;
            final byte[] qualValueBase;
            QualModel qualForWrite;
            byte[] qualBytes;

            logMethodName =
                LOG.enter(()->"buildVersioningDerivedQualifiers(fqpSet=",
                          ()->fqpSet,
                          ()->",scheme=",
                          ()->verModel,
                          ()->",ver=",
                          ()->singleVersion,
                          ()->")");

            // we're going to be creating new byte arrays here via concatenation anyway, so
            // skip doing the defensive copy
            qualValueBase = colQual.getName().getValue(DefensiveCopyStrategy.NEVER);

            /*
             * For the versioning scheme specified by the model, add a qualifier to the read spec,
             * modified to accommodate the versioning scheme, if necessary.
             */
            // create a new qualifier with the version number appended
            qualBytes =
                HBaseUtil.appendVersionToQual(qualValueBase,
                                              singleVersion.longValue(),
                                              verModel);
            qualForWrite = QualModel.of(Name.of(qualBytes, DefensiveCopyStrategy.NEVER));
            // create a new family+qualifier pair pairing the existing column family
            // with the newly-created qualifier
            fqpSet.add(FamilyQualifierPair.of(colFam, qualForWrite));
            LOG.trace(logMethodName, ()->"fqpSet=", ()->fqpSet);

            LOG.leave(logMethodName);
        }

        /**
         * TODO: javadoc
         * @param fqpSet
         * @param colFam
         * @param colQual
         * @return
         */
        private Set<FamilyQualifierPair> prepareHBaseOpQualifierSet(final Set<FamilyQualifierPair> fqpSet, final FamilyHB colFam, final QualHB colQual) {
            /*
             * Intended for the case where the logic to add versioning-derived qualifiers indicates
             * that qualifier versioning is not in use; in that case, adds a single pair using the
             * base family and qualifier.
             */
            if (fqpSet.size() <= 0) {
                fqpSet.add(FamilyQualifierPair.of(colFam, colQual));
            }
            return Collections.unmodifiableSet(fqpSet);
        }

        /**
         * TODO: javadoc
         * @param colSpec
         * @param colFam
         * @param colQual
         * @param dcs
         * @return
         */
        private Set<FamilyQualifierPair> getVersionAdjustedQualifiersForWrite(final ColSpecWriteFrozen colSpec, final FamilyHB colFam, final QualHB colQual, final DefensiveCopyStrategy dcs) {
            final String logMethodName;
            final Set<FamilyQualifierPair> fqpSet;
            final Long singleVersion;
            VersioningModel versioningScheme;
            final Set<FamilyQualifierPair> fqpSetComplete;

            logMethodName =
                LOG.enter(()->"getVersionAdjustedQualifiersForWrite(",
                          colSpec::toString,
                          ()->")");

            fqpSet = new HashSet<>();
            singleVersion = colSpec.getVersion();
            LOG.trace(logMethodName, ()->"version=", ()->singleVersion);

            if (singleVersion != null) {
                versioningScheme = SpecUtil.determineVersioningScheme(colFam, colQual);
                LOG.trace(logMethodName, ()->"version-scheme=", ()->versioningScheme);

                if (VersioningModel.isQualifierBased(versioningScheme)) {
                    buildVersioningDerivedQualifiers(fqpSet,
                                                     versioningScheme,
                                                     colFam,
                                                     colQual,
                                                     singleVersion);
                }
            }

            LOG.trace(logMethodName, ()->"fqpSet=", ()->fqpSet);
            fqpSetComplete = prepareHBaseOpQualifierSet(fqpSet, colFam, colQual);

            LOG.trace(logMethodName, ()->"fqpSetComplete=", ()->fqpSetComplete);
            LOG.leave(logMethodName);

            return fqpSetComplete;
        }

        /**
         * TODO
         * @param colSpec
         * @param colFam
         * @param colQual
         * @param dcs
         * @return
         */
        private Set<FamilyQualifierPair> getVersionAdjustedQualifiersForRead(final ColSpecRead<ReadOpSpecDefault> colSpec, final FamilyHB colFam, final QualHB colQual, final DefensiveCopyStrategy dcs) {
            final Set<FamilyQualifierPair> fqpSet;
            final LongValueSpec<?> version;
            final Long singleVersion;
            VersioningModel versioningScheme;

            fqpSet = new HashSet<>();
            version = colSpec.getVersion();
            if (version != null) {
                versioningScheme = SpecUtil.determineVersioningScheme(colFam, colQual);
                if (VersioningModel.isQualifierBased(versioningScheme)) {
                    singleVersion = version.singleValue();
                    if (singleVersion == null) {
                        /*
                        buildVersioningDerivedQualifiers(fqpSet,
                                                         versioningScheme,
                                                         colFam,
                                                         colQual,
                                                         version);
                                                         */
                    } else {
                        buildVersioningDerivedQualifiers(fqpSet,
                                                         versioningScheme,
                                                         colFam,
                                                         colQual,
                                                         singleVersion);
                    }
                }
            }
            return prepareHBaseOpQualifierSet(fqpSet, colFam, colQual);
        }

        private ApplicableVersion getQualifierBasedVersion(final ColSpecRead<ReadOpSpecDefault> colSpec, final FamilyHB colFam, final QualHB colQual) {
            LongValueSpec<ColSpecRead<ReadOpSpecDefault>> versionUnrestricted;
            LongValueSpecFrozen version;
            VersioningModel versioningScheme;

            versioningScheme = SpecUtil.determineVersioningScheme(colFam, colQual);
            if (VersioningModel.isQualifierBased(versioningScheme)) {
                version = colSpec.getVersion();
                if (version != null) {
                    return new ApplicableVersion(versioningScheme, version);
                } else {
                    /*
                     * If this column uses qualifier-based versioning, then any column qualifiers
                     * will be written to HBase with version numbers appended, so there is no way
                     * to query solely by the "stem" qualifier name itself. Therefore, we need to
                     * query using ColumnRanges and Filters to bound the *range* of qualifiers,
                     * even in the case where the read itself does not specify a version range. In
                     * that case, mock up an unrestricted ("all versions") version long spec:
                     */
                    versionUnrestricted = VersioningModel.buildLongValueSpecForQualVersioning(colSpec);
                    versionUnrestricted.freezeRecursive();
                    return new ApplicableVersion(versioningScheme, versionUnrestricted);
                }
            }
            return null;
        }

        /**
         * TODO
         * @param logMethodName
         * @param dcs
         * @param readGet
         * @param colSpec
         */
        private void addColumn(final String logMethodName, final DefensiveCopyStrategy dcs, final Get readGet, final ColSpecRead<ReadOpSpecDefault> colSpec) {
            final ReadOpSpecFrozen readOpSpec;
            final FamilyHB colFam;
            final QualHB colQual;
            FamilyHB readFam;
            QualHB readQual;
            ColumnRange readColumnRange;
            final byte[] famValue;
            final Set<FamilyQualifierPair> fqpSet;

            if (colSpec != null) {
                readOpSpec = colSpec.getParent();
                colFam = colSpec.getFamily();
                colQual = colSpec.getColumn();
                if (colFam != null) {
                    famValue = colFam.getName().getValue(dcs);
                    if (colQual != null) {
                        // Generate the set of family-qualifier pairs to which the given column
                        // specification refers, adding any version-specific adjustments to the
                        // qualifier values as needed
                        fqpSet =
                            getVersionAdjustedQualifiersForRead(colSpec, colFam, colQual, dcs);
                        for (FamilyQualifierPair fqp : fqpSet) {
                            readFam = fqp.getFamily();
                            readQual = fqp.getColumn();
                            /*
                             * TODO
                             * it might be worth investigating modifying
                             * getVersionAdjustedQualifiersForRead such that it guarantees that the
                             * return values are already defensive copies, to avoid the possibility
                             * of duplicate defensive-copying here
                             */
                            readGet.addColumn(fqp.getFamily().getName().getValue(dcs),
                                              fqp.getColumn().getName().getValue(dcs));

                            // Update the parent read operation spec to associate it with this
                            // family-qualifier pair
                            readOpSpec.addColumnAssoc(fqp, colSpec);

                            LOG.trace(logMethodName,
                                      ()->"adding to GET: family=",
                                      fqp::getFamily,
                                      ()->", column=",
                                      fqp::getColumn,
                                      ()->" --> ",
                                      ()->colSpec);
                        }
                    } else {
                        /*
                         * TODO: versioning on qualifier when reading from a full family?
                         */
                        readGet.addFamily(famValue);

                        // Update the parent read operation spec to associate it with this
                        // column family reference
                        readOpSpec.addColumnAssoc(colFam, colSpec);

                        LOG.trace(logMethodName,
                                  ()->"adding to GET: family=",
                                  ()->colFam,
                                  ()->" --> ",
                                  ()->colSpec);
                    }
                }
            }
        }

        private void updateGetColumnGroupings(final GetColumnGrouping gcg, final DefensiveCopyStrategy dcs, final ColSpecRead<ReadOpSpecDefault> colSpec) {
            final String logMethodName;
            final ReadOpSpecFrozen readOpSpec;
            final FamilyHB colFam;
            final QualHB colQual;
            final byte[] famValue;
            final ApplicableVersion version;
            final Set<FamilyQualifierPair> fqpSet;
            final Set<ColumnRange> colRangeSet;
            final FamilyQualifierPair fqp;

            logMethodName =
                LOG.enter(()->"updateGetColumnGroupings(colSpec=",
                          ()->colSpec,
                          ()->")");

            if (colSpec != null) {
                readOpSpec = colSpec.getParent();
                colFam = colSpec.getFamily();
                colQual = colSpec.getColumn();
                if (colFam != null) {
                    famValue = colFam.getName().getValue(dcs);
                    if (colQual != null) {
                        version = getQualifierBasedVersion(colSpec, colFam, colQual);
                        if (version == null) {
                            fqp = FamilyQualifierPair.of(colFam, colQual);
                            gcg.addFQP(fqp);
                            // Update the parent read operation spec to associate it with this
                            // family-qualifier pair
                            readOpSpec.addColumnAssoc(fqp, colSpec);
                            LOG.trace(logMethodName,
                                      ()->"adding to get-group: family=",
                                      fqp::getFamily,
                                      ()->", column=",
                                      fqp::getColumn,
                                      ()->" --> ",
                                      ()->colSpec);
                        } else if (version.getVersion().isSingleValue()) {
                            fqpSet = new HashSet<>();
                            buildVersioningDerivedQualifiers(
                                fqpSet,
                                version.getScheme(),
                                colFam,
                                colQual,
                                version.getVersion().singleValue());
                            gcg.addAllFQP(fqpSet);
                            for (FamilyQualifierPair fqpDerived : fqpSet) {
                                // Update the parent read operation spec to associate it with this
                                // family-qualifier pair
                                readOpSpec.addColumnAssoc(fqpDerived, colSpec);
                                LOG.trace(logMethodName,
                                          ()->"adding to get-group: family=",
                                          fqpDerived::getFamily,
                                          ()->", column=",
                                          fqpDerived::getColumn,
                                          ()->" --> ",
                                          ()->colSpec);
                            }
                        } else {
                            colRangeSet = new HashSet<>();
                            buildVersioningDerivedQualifiers(
                                colRangeSet,
                                version.getScheme(),
                                colFam,
                                colQual,
                                version.getVersion());
                            gcg.addAllColumnRange(colRangeSet);
                            for (ColumnRange colRangeDerived : colRangeSet) {
                                readOpSpec.addColumnRangeAssoc(colRangeDerived, colSpec);
                                LOG.trace(logMethodName,
                                          ()->"adding to get-group: family=",
                                          colRangeDerived::getFamily,
                                          ()->", column-range=",
                                          colRangeDerived::toString,
                                          ()->" --> ",
                                          ()->colSpec);
                            }
                        }
                    } else {
                        /*
                         * TODO: versioning on qualifier when reading from a full family?
                         */
                        gcg.addFamily(colFam);
                        // Update the parent read operation spec to associate it with this
                        // column family reference
                        readOpSpec.addColumnAssoc(colFam, colSpec);
                        LOG.trace(logMethodName,
                                  ()->"adding to get-group: family=",
                                  ()->colFam,
                                  ()->" --> ",
                                  ()->colSpec);
                    }
                }
            }
            LOG.leave(logMethodName);
        }

        /**
         * TODO: javadoc
         * @param colSpec
         * @return
         */
        private Long determineWriteTimestamp(final ColSpecWriteFrozen colSpec) {
            final Long version;
            final VersioningModel verScheme;

            version = colSpec.getVersion();
            verScheme = SpecUtil.determineVersioningScheme(colSpec);
            if ((version != null) && (VersioningModel.isTimestampBased(verScheme))) {
                /*
                 * (NOTE: this list of TODOs copied from setReadTimestamp, because the issues they
                 * reference will need to addressed concurrently on both the read and write sides)
                 *
                 * TODO: Determine how to support TIMESTAMP_CHRONO, which would need to subtract
                 *     the version numbers from Long.MAX_VALUE and invert the range. Not
                 *     implemented yet.
                 * TODO: should probably throw some kind of validation exception earlier if both
                 *     timestamp and version information are specified, and the versioning conflict
                 *     specifies to use the timestamp... that is never a valid configuration, as
                 *     the literally-specified timestamp will always be overridden by the
                 *     versioning-specified timestamp
                 */
                return version;
            } else {
                return colSpec.getTS();
            }
        }

        /**
         * TODO
         * @param logMethodName
         * @param dcs
         * @param writePut
         * @param colSpec
         */
        private void addColumn(final String logMethodName, final DefensiveCopyStrategy dcs, final Put writePut, final ColSpecWriteFrozen colSpec) {
            final Long writeTS;
            final FamilyHB colFam;
            final QualHB colQual;
            final NullableValue colValue;
            final Set<FamilyQualifierPair> fqpSet;
            
            writeTS = determineWriteTimestamp(colSpec);
            colFam = colSpec.getFamily();
            colQual = colSpec.getColumn();
            colValue = colSpec.getValue();

            fqpSet = getVersionAdjustedQualifiersForWrite(colSpec, colFam, colQual, dcs);
            for (FamilyQualifierPair fqp : fqpSet) {
                if (writeTS == null) {
                    writePut.add(fqp.getFamily().getName().getValue(dcs),
                                 fqp.getColumn().getName().getValue(dcs),
                                 colValue.getValue(dcs));
                    LOG.trace(logMethodName,
                              () -> "adding to PUT: family=",
                              fqp::getFamily,
                              () -> ", column=",
                              fqp::getColumn,
                              () -> ", value='",
                              () -> colValue,
                              () -> "'");
                } else {
                    writePut.add(fqp.getFamily().getName().getValue(dcs),
                                 fqp.getColumn().getName().getValue(dcs),
                                 writeTS.longValue(),
                                 colValue.getValue(dcs));
                    LOG.trace(logMethodName,
                              () -> "adding to PUT: family=",
                              fqp::getFamily,
                              () -> ", column=",
                              fqp::getColumn,
                              () -> ", value='",
                              () -> colValue,
                              () -> "', ts=",
                              () -> writeTS);
                }
            }
        }

        /**
         * TODO
         * @param logMethodName
         * @param opName
         * @param condMutateOp
         * @param mutateOp
         * @param tableRowSpec
         * @param colWriteList
         * @param condition
         * @param writeMutation
         * @param dcs
         * @param <M>
         * @return
         * @throws HBaseTableRowException
         */
        private <M extends Mutation> boolean performMutation(final String logMethodName, final String opName, final HBaseCheckAndMutate<M> condMutateOp, final HBaseMutate<M> mutateOp, final RowSpec<WriteOpSpecDefault> tableRowSpec, final List<ColSpecWriteFrozen> colWriteList, final CondSpec<?> condition, final M writeMutation, final DefensiveCopyStrategy dcs) throws HBaseTableRowException {
            final String logMsg;
            final NullableValue condPossibleValue;
            final RowKey rowKey;
            final FamilyHB fam;
            final QualHB qual;
            final boolean writeCompleted;

            try {
                if (condition != null) {
                    LOG.trace(logMethodName,
                              ()->"on-condition: ",
                              ()->condition);
                    condPossibleValue = condition.getValue();
                    rowKey = condition.getRowKey();
                    fam = condition.getFamily();
                    qual = condition.getColumn();

                    LOG.trace(logMethodName, ()->"performing ", ()->opName, ()->"...");
                    /*
                     * It's okay to use NullableValue#getValue here without disambiguating Value
                     * vs. Empty, as both are immutable, and the constructor for the former
                     * enforces that getValue must return NON-NULL, and the constructor for the
                     * latter enforces that getValue must return NULL. Thus, getValue returns what
                     * checkAndPut needs in either case.
                     */
                    writeCompleted =
                        condMutateOp.checkAndMutate(tableRowSpec.getLiteralizedRowKeyBytes(),
                                                    fam.getName().getValue(dcs),
                                                    qual.getName().getValue(dcs),
                                                    condPossibleValue.getValue(dcs),
                                                    writeMutation);
                } else {
                    LOG.trace(logMethodName, ()->"performing ", ()->opName, ()->"...");
                    mutateOp.mutate(writeMutation);
                    writeCompleted = true;
                }
                LOG.trace(logMethodName,
                          ()->opName,
                          ()->" operation response: ",
                          ()->Boolean.toString(writeCompleted));
            } catch (IOException ioExc) {
                logMsg = (opName.toUpperCase()
                          + " failure"
                          + ((condition == null)
                             ?"; "
                             :" (with condition: " + condition + "); ")
                          + ioExc);
                LOG.error(logMethodName, logMsg, ioExc);
                if ((colWriteList != null) && (!colWriteList.isEmpty())) {
                    throw new HBaseMultiColumnException(tableRowSpec, colWriteList, logMsg, ioExc);
                } else {
                    throw new HBaseTableRowException(tableRowSpec, logMsg, ioExc);
                }
            }
            return writeCompleted;
        }

        /**
         * TODO
         * @param logMethodName
         * @param writeToTable
         * @param tableRowSpec
         * @param colWriteList
         * @param condition
         * @param writePut
         * @param dcs
         * @return
         * @throws HBaseMultiColumnException
         */
        private boolean performWrite(final String logMethodName, final HTable writeToTable, final RowSpec<WriteOpSpecDefault> tableRowSpec, final List<ColSpecWriteFrozen> colWriteList, final CondSpec<?> condition, final Put writePut, final DefensiveCopyStrategy dcs) throws HBaseTableRowException {
            return performMutation(logMethodName,
                                   "write",
                                   writeToTable::checkAndPut,
                                   writeToTable::put,
                                   tableRowSpec,
                                   colWriteList,
                                   condition,
                                   writePut,
                                   dcs);
        }

        /**
         * TODO
         * @param logMethodName
         * @param writeToTable
         * @param tableRowSpec
         * @param colWriteList
         * @param condition
         * @param writeDel
         * @param dcs
         * @return
         * @throws HBaseTableRowException
         */
        private boolean performDelete(final String logMethodName, final HTable writeToTable, final RowSpec<WriteOpSpecDefault> tableRowSpec, final List<ColSpecWriteFrozen> colWriteList, final CondSpec<?> condition, final Delete writeDel, final DefensiveCopyStrategy dcs) throws HBaseTableRowException {
            return performMutation(logMethodName,
                                   "delete",
                                   writeToTable::checkAndDelete,
                                   writeToTable::delete,
                                   tableRowSpec,
                                   colWriteList,
                                   condition,
                                   writeDel,
                                   dcs);
        }

        /**
         * TODO
         * @param logMethodName
         * @param readGet
         * @param readSpec
         * @param tableRowSpec
         * @throws HBaseTableRowException
         */
        private void setReadTimestamp(final String logMethodName, final Get readGet, final ReadOpSpecFrozen readSpec, final RowRef tableRowSpec) throws HBaseTableRowException {
            String logMsg;
            final LongValueSpecFrozen commonVer;
            final VersioningModel commonVerConf;
            final LongValueSpecFrozen timestamp;

            commonVer = readSpec.getCommonVersion();
            commonVerConf = readSpec.getCommonVersioningConfig();

            if ((commonVer != null)
                && (commonVerConf != null)
                && (VersioningModel.isTimestampBased(commonVerConf))) {
                /*
                 * TODO: Determine how to support TIMESTAMP_CHRONO, which would need to subtract
                 *     the version numbers from Long.MAX_VALUE and invert the range. Not
                 *     implemented yet.
                 * TODO: should probably throw some kind of validation exception earlier if both
                 *     timestamp and version information are specified, and the versioning conflict
                 *     specifies to use the timestamp... that is never a valid configuration, as
                 *     the literally-specified timestamp will always be overridden by the
                 *     versioning-specified timestamp
                 */
                if (commonVerConf == VersioningModel.TIMESTAMP_CHRONO) {
                    logMsg = "Chronological versioning via the timestamp ("
                             + VersioningModel.class.getSimpleName()
                             + "."
                             + VersioningModel.TIMESTAMP_CHRONO
                             + " is not yet implemented";
                    throw new UnsupportedOperationException(logMsg);
                }
                // TODO: this code assumes TIMESTAMP_LATEST is the only versioning scheme
                timestamp = commonVer;
            } else {
                timestamp = readSpec.getAtTime();
            }
            try {
                ReadUtils.applyTS(readGet, timestamp);
                LOG.trace(logMethodName,
                          ()->"applied timestamp/version constraints (if applicable): ts=",
                          readSpec::getAtTime,
                          ()->",common-version=",
                          ()->commonVer,
                          ()->",common-versioning-config=",
                          readSpec::getCommonVersioningConfig);
            } catch (IOException ioExc) {
                logMsg = "Failed to apply timestamp cond to READ per spec: "
                         + readSpec + "; " + ioExc;
                LOG.error(logMethodName, logMsg, ioExc);
                throw new HBaseTableRowException(tableRowSpec, logMsg, ioExc);
            }
        }

        private Get buildGet(final RowRef rowRef, final ColumnRange colRange, final DefensiveCopyStrategy dcs) {
            final String logMethodName;
            final Get readGet;
            final byte[] rowKeyBytes;
            final Filter combinedFilter;
            final Filter lowerFilter;
            final Filter higherFilter;

            logMethodName =
                LOG.enter(()->"buildGet(rowRef=",
                          ()->rowRef,
                          ()->",colRange=",
                          ()->colRange,
                          ()->")");
            lowerFilter =
                new QualifierFilter(colRange.getLowerComparator(),
                                    new BinaryComparator(
                                        colRange
                                            .getLower()
                                            .getName()
                                            .getValue(dcs)));
            higherFilter =
                new QualifierFilter(colRange.getHigherComparator(),
                                    new BinaryComparator(
                                        colRange
                                            .getHigher()
                                            .getName()
                                            .getValue(dcs)));
            combinedFilter = new FilterList(lowerFilter, higherFilter);
            rowKeyBytes = rowRef.getLiteralizedRowKeyBytes();
            readGet = new Get(rowKeyBytes);
            readGet.addFamily(colRange.getFamily().getName().getValue(dcs));
            readGet.setFilter(combinedFilter);
            LOG.trace(logMethodName, ()->"get:", ()->readGet);
            LOG.leave(logMethodName);
            return readGet;
        }

        private void addSetupParamsToGet(final Get getForSetup, final ReadOpSpecFrozen readSpec, final RowRef tableRowSpec) throws HBaseTableRowException {
            final String logMethodName;
            final Integer maxResultsPerFamily;

            logMethodName =
                LOG.enter(()->"addSetupParamsToGet(get=",
                          ()->getForSetup,
                          ()->",readSpec=",
                          ()->readSpec,
                          ()->",row=",
                          ()->tableRowSpec,
                          ()->")");
            maxResultsPerFamily = readSpec.getMaxEntriesPerFamily();
            if (maxResultsPerFamily != null) {
                getForSetup.setMaxResultsPerColumnFamily(maxResultsPerFamily.intValue());
                LOG.trace(logMethodName,
                          () -> "applied maximum number of columns to read per family: ",
                          () -> maxResultsPerFamily);
            }
            setReadTimestamp(logMethodName, getForSetup, readSpec, tableRowSpec);
            LOG.leave(logMethodName);
        }

        /**
         * TODO
         * @param readSpec
         * @return
         * @throws IllegalArgumentException
         * @throws HBaseException
         * @throws HBaseRuntimeException
         */
        public Iterable<Result> exec(final ReadOpSpecDefault readSpec) throws IllegalArgumentException, HBaseException, HBaseRuntimeException {
            String logMsg;
            final String logMethodName;
            final DefensiveCopyStrategy dcs;
            final RowSpec<?> tableRowSpec;
            final GetColumnGrouping gcg;
            final List<Get> allReadGets;
            Get readGet;
            final List<ColSpecRead<ReadOpSpecDefault>> colReadList;
            final List<Result> resList;
            final byte[] rowKeyBytes;
            
            Util.ensureNotNull(readSpec, this, "readSpec", ReadOpSpecDefault.class);
            
            logMethodName =
                LOG.enter(()->"exec(READ:",
                          ()->String.valueOf(readSpec.getHandle()),
                          ()->")");
            
            // Ensure that the spec contains all required attributes for a READ operation
            verifyStateForExec(readSpec);
            
            dcs = HBaseControl.this.context.getDefensiveCopyStrategy();
            LOG.trace(logMethodName,
                      ()->"defensive-copying: ",
                      ()->String.valueOf(dcs));
            
            tableRowSpec = readSpec.getTableRow();
            LOG.trace(logMethodName,
                      ()->"table-row: ",
                      ()->tableRowSpec);

            colReadList = readSpec.getWithColumn();
            LOG.trace(logMethodName,
                      ()->"columns: ",
                      ()->colReadList,
                      ()->"; determining column groupings...");
            gcg = new GetColumnGrouping();
            if (colReadList != null) {
                for (ColSpecRead<ReadOpSpecDefault> colSpec : colReadList) {
                    updateGetColumnGroupings(gcg, dcs, colSpec);
                }
            }
            LOG.trace(logMethodName,
                      ()->"column associations (full family): ",
                      readSpec::getFullFamilyAssoc);
            LOG.trace(logMethodName,
                      ()->"column associations (family+qualifier): ",
                      readSpec::getFamilyQualifierAssoc);
            LOG.trace(logMethodName,
                      ()->"column associations (range): ",
                      readSpec::getColumnRangeAssoc);

            LOG.trace(logMethodName, ()->"building Get objects...");
            allReadGets = new LinkedList<>();

            if ((gcg.hasFamilies()) || (gcg.hasFQPs())) {
                rowKeyBytes = tableRowSpec.getLiteralizedRowKeyBytes();
                LOG.trace(logMethodName,
                          () -> "building main Get object for columns which require NO filter...");
                readGet = new Get(rowKeyBytes);
                for (FamilyHB family : gcg.getFamilySet()) {
                    readGet.addFamily(family.getName().getValue(dcs));
                }
                for (FamilyQualifierPair fqp : gcg.getFQPSet()) {
                    readGet.addColumn(fqp.getFamily().getName().getValue(dcs),
                                      fqp.getColumn().getName().getValue(dcs));
                }
                allReadGets.add(readGet);
                LOG.trace(logMethodName,
                          () -> "main (unfiltered) Get: ",
                          () -> readGet);
            }

            if (gcg.hasColumnRanges()) {
                LOG.trace(logMethodName,
                          () -> "building separate Get objects for columns requiring a filter for ",
                          () -> ColumnRange.class.getSimpleName(),
                          () -> "...");
                for (ColumnRange colRange : gcg.getColumnRangeSet()) {
                    allReadGets.add(buildGet(tableRowSpec, colRange, dcs));
                }
            }

            LOG.trace(logMethodName, ()->"ALL Get objects: ", ()->allReadGets);

            for (Get getForSetup : allReadGets) {
                addSetupParamsToGet(getForSetup, readSpec, tableRowSpec);
            }

            try (ManagedTable readFromTable =
                     resMgr.borrow(HBaseControl.this.context, tableRowSpec.getTable())) {

                LOG.trace(logMethodName, ()->"table obtained");

                resList = new LinkedList<>();
                for (Get getToExec : allReadGets) {
                    LOG.trace(logMethodName, () -> "performing read (using: ", ()->getToExec);
                    try {
                        // perform the HBase READ operation and return the result
                        resList.add(readFromTable.use().get(getToExec));
                    } catch (IOException ioExc) {
                        logMsg = "READ failed; " + ioExc;
                        LOG.error(logMethodName, logMsg, ioExc);
                        throw new HBaseMultiColumnException(tableRowSpec, colReadList, logMsg, ioExc);
                    }
                }
            } catch (HBaseException | HBaseRuntimeException exc) {
                // already logged; just rethrow to get out of the current try block
                throw exc;
            } catch (Exception exc) {
                logMsg = "Unexpected failure during READ operation ("
                         + readSpec
                         + "): "
                         + exc.toString();
                LOG.error(logMsg, logMethodName, exc);
                throw new HBaseRuntimeException(logMsg, exc);
            } finally {
                LOG.leave(logMethodName);
            }
            return resList;
        }
        
        /**
         * 
         * @param writeSpec
         * @return
         * @throws IllegalArgumentException
         * @throws IllegalStateException
         * @throws HBaseException
         * @throws HBaseRuntimeException
         */
        public boolean exec(final WriteOpSpecDefault writeSpec) throws IllegalArgumentException, IllegalStateException, HBaseException, HBaseRuntimeException {
            String logMsg;
            final String logMethodName;
            final DefensiveCopyStrategy dcs;
            final RowSpec<WriteOpSpecDefault> tableRowSpec;
            final List<ColSpecWriteFrozen> colWriteList;
            final CondSpec<?> condition;
            final byte[] rowKeyBytes;
            final Put writePut;
            final Delete writeDel;
            final Long ttl;
            boolean writeCompleted;
            
            Util.ensureNotNull(writeSpec, this, "writeSpec", WriteOpSpecDefault.class);
            
            logMethodName =
                LOG.enter(()->"exec(WRITE:",
                          ()->String.valueOf(writeSpec.getHandle()),
                          ()->")");
            writeCompleted = false;
            
            // Ensure that the spec contains all required attributes for a WRITE operation
            verifyStateForExec(writeSpec);
            
            dcs = HBaseControl.this.context.getDefensiveCopyStrategy();
            LOG.trace(logMethodName,
                      ()->"defensive-copying: ",
                      ()->String.valueOf(dcs));
            
            tableRowSpec = writeSpec.getTableRow();
            LOG.trace(logMethodName,
                    ()->"table-row: ",
                    ()->tableRowSpec);

            try (ManagedTable writeToTable =
                    resMgr.borrow(HBaseControl.this.context, tableRowSpec.getTable())) {
                LOG.trace(logMethodName, ()->"table obtained");

                rowKeyBytes = tableRowSpec.getLiteralizedRowKeyBytes();
                condition = writeSpec.getGivenCondition();

                if (writeSpec.isDeleteRow()) {
                    writeCompleted =
                        this.performDelete(logMethodName,
                                           writeToTable.use(),
                                           tableRowSpec,
                                           null,
                                           condition,
                                           new Delete(rowKeyBytes),
                                           dcs);

                } else {
                    writePut = new Put(rowKeyBytes);

                    ttl = writeSpec.getTTL();
                    if (ttl == null) {
                        LOG.trace(logMethodName, () -> "no TTL specified (infinite retention)");
                    } else {
                        LOG.trace(logMethodName, () -> "TTL assigned: ", () -> ttl);
                        writePut.setTTL(ttl.longValue());
                    }

                    colWriteList = writeSpec.getWithColumn();
                    LOG.trace(logMethodName,
                              () -> "columns: ",
                              () -> colWriteList);
                    if (colWriteList != null) {
                        for (ColSpecWriteFrozen colWrite : colWriteList) {
                            addColumn(logMethodName, dcs, writePut, colWrite);
                        }
                    }

                    writeCompleted =
                        this.performWrite(logMethodName,
                                          writeToTable.use(),
                                          tableRowSpec,
                                          colWriteList,
                                          condition,
                                          writePut,
                                          dcs);
                }
            } catch (HBaseException | HBaseRuntimeException exc) {
                throw exc;
            } catch (Exception exc) {
                logMsg = "Unexpected failure during WRITE operation ("
                         + writeSpec
                         + "): "
                         + exc.toString();
                LOG.error(logMethodName, logMsg, exc);
                throw new HBaseException(logMsg, exc);
            }
            return writeCompleted;
        }
        
        /**
         * Tunnelling method so that OperationController with access to the delegate can use the
         * execution thread pool established in the HBaseControl.
         * @param operationExecutable
         * @return
         * @throws UnsupportedOperationException
         */
        public ListenableFuture<OpResultSet> execAsync(Callable<OpResultSet> operationExecutable) throws UnsupportedOperationException {
            String logMsg;
            final ListeningExecutorService asyncPool;
            final ListenableFuture<OpResultSet> execTask;
            asyncPool = HBaseControl.this.execPool;
            if (asyncPool == null) {
                logMsg = HBaseControl.class.getSimpleName()
                         + " (context.id='"
                         + HBaseControl.this.context.getId()
                         + "') does not support asynchronous operations";
                throw new UnsupportedOperationException(logMsg);
            }
            execTask = asyncPool.submit(operationExecutable);
            return execTask;
        }
        
        /**
         * Use a private constructor so that the enclosing HBaseControl instance can control who
         * has access to the delegate (and, consequently, who can execute HBase operations based
         * upon specifications).
         */
        private HBaseDelegate() { }
    }
    
    // ||----(inner classes: instance)-----------------------------------------------------------||
    
    // ||========================================================================================||
    // ||    CONSTANTS                                                                           ||
    // ||----------------------------------------------------------------------------------------||
    
    private static final long DEFAULT_THREADPOOL_IDLEEXPIRE = 60L * 1000L; // 60s
    private static final TimeUnit DEFAULT_THREADPOOL_IDLEEXPIRE_UNIT = TimeUnit.MILLISECONDS;
    
    private static final JitLog LOG;
    
    // ||----(constants)-------------------------------------------------------------------------||
    
    // ||========================================================================================||
    // ||    STATIC METHODS                                                                      ||
    // ||----------------------------------------------------------------------------------------||
    
    private static void verifyStateForExec(final OperationSpec<?> opSpec) throws IllegalStateException {
        final String logMethodName;
        
        logMethodName =
            LOG.enter(()->"verifyStateForExec(spec:",
                      ()->opSpec,
                      ()->")");
        if (!opSpec.isFrozen()) {
            throw new IllegalStateException(opSpec.getClass().getSimpleName()
                                            + " must be frozen before spec may be executed"); 
        }
        LOG.leave(logMethodName);
    }
    
    // ||----(static methods)--------------------------------------------------------------------||
    
    // ||========================================================================================||
    // ||    STATIC INITIALIZER                                                                  ||
    // ||----------------------------------------------------------------------------------------||
    
    static {
        LOG = new JitLog(HBaseControl.class);
    }
    
    // ||----(static initializer)----------------------------------------------------------------||
    
    // ||========================================================================================||
    // ||    INSTANCE PROPERTIES                                                                 ||
    // ||----------------------------------------------------------------------------------------||
    
    private final HBaseContext context;
    private final HBaseResourceManager resMgr;
    private final HBaseDelegate delegate;
    private final ListeningExecutorService execPool;
    
    // ||----(instance properties)---------------------------------------------------------------||
    
    // ||========================================================================================||
    // ||    INSTANCE METHODS                                                                    ||
    // ||----------------------------------------------------------------------------------------||
    
    /**
     * Shut down the asynchronous execution pool, if one was created.
     * @see java.io.Closeable#close()
     */
    @Override
    public void close() {
        if (this.execPool != null) {
            this.execPool.shutdown();
        }
    }
    
    /**
     * 
     * @return
     */
    public HBaseContext getContext() {
        return this.context;
    }
    
    /**
     * {@inheritDoc}
     * @see {@link HBaseStart#begin()}.
     */
    public OperationController<OpResultSet> begin() {
        return new OperationControllerDefault(this.delegate, this.context);
    }
    
    // ||----(instance methods)------------------------------------------------------------------||

    // ||========================================================================================||
    // ||    CONSTRUCTORS                                                                        ||
    // ||----------------------------------------------------------------------------------------||
    
    public HBaseControl(final HBaseContext context, final HBaseResourceManager resMgr) {
        Util.ensureNotNull(context, this, "context", HBaseContext.class);
        this.context = context;
        Util.ensureNotNull(resMgr, this, "resMgr", HBaseResourceManager.class);
        this.resMgr = resMgr;
        this.delegate = new HBaseDelegate();
        if (context.getAsyncConfig().isAsyncEnabled()) {
            this.execPool =
                MoreExecutors.listeningDecorator(
                    new ThreadPoolExecutor(context.getAsyncConfig().getMinSizeForThreadPool(),
                                           context.getAsyncConfig().getMaxSizeForThreadPool(),
                                           DEFAULT_THREADPOOL_IDLEEXPIRE,
                                           DEFAULT_THREADPOOL_IDLEEXPIRE_UNIT,
                                           new SynchronousQueue<Runnable>()));
        } else {
            this.execPool = null;
        }
    }
    
    // ||----(constructors)----------------------------------------------------------------------||
}
