// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.
package com.starrocks.sql.common;


import com.clearspring.analytics.util.Lists;
import com.clearspring.analytics.util.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import com.starrocks.analysis.DateLiteral;
import com.starrocks.analysis.LiteralExpr;
import com.starrocks.catalog.PartitionKey;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.util.DateUtils;
import com.starrocks.common.util.RangeUtils;
import com.starrocks.sql.analyzer.SemanticException;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Process lower bound and upper bound for Expression Partition,
 * only support SlotRef and FunctionCallExpr
 */
public class SyncPartitionUtils {

    public static final String MINUTE = "minute";
    public static final String HOUR = "hour";
    public static final String DAY = "day";
    public static final String MONTH = "month";
    public static final String QUARTER = "quarter";
    public static final String YEAR = "year";

    private SyncPartitionUtils() throws IllegalAccessException {
        throw new IllegalAccessException("Utility class");
    }

    private static final String DEFAULT_PREFIX = "p";

    public static PartitionDiff calcSyncSamePartition(Map<String, Range<PartitionKey>> baseRangeMap,
                                                      Map<String, Range<PartitionKey>> mvRangeMap) {
        // This synchronization method has a one-to-one correspondence
        // between the base table and the partition of the mv.
        Map<String, Range<PartitionKey>> adds = diffRange(baseRangeMap, mvRangeMap);
        Map<String, Range<PartitionKey>> deletes = diffRange(mvRangeMap, baseRangeMap);
        return new PartitionDiff(adds, deletes);
    }

    public static PartitionDiff calcSyncRollupPartition(Map<String, Range<PartitionKey>> baseRangeMap,
                                                        Map<String, Range<PartitionKey>> mvRangeMap,
                                                        String granularity, PrimitiveType partitionType) {
        Map<String, Range<PartitionKey>> rollupRange = mappingRangeList(baseRangeMap, granularity, partitionType);
        Map<String, Set<String>> partitionRefMap = generatePartitionRefMap(rollupRange, baseRangeMap);
        Map<String, Range<PartitionKey>> adds = diffRange(rollupRange, mvRangeMap);
        Map<String, Range<PartitionKey>> deletes = diffRange(mvRangeMap, rollupRange);
        PartitionDiff diff = new PartitionDiff(adds, deletes);
        diff.setRollupToBasePartitionMap(partitionRefMap);
        return diff;

    }

    public static Map<String, Range<PartitionKey>> mappingRangeList(Map<String, Range<PartitionKey>> baseRangeMap,
                                                              String granularity, PrimitiveType partitionType) {
        Set<LocalDateTime> timePointSet = Sets.newTreeSet();
        for (Map.Entry<String, Range<PartitionKey>> rangeEntry : baseRangeMap.entrySet()) {
            PartitionMapping mappedRange = mappingRange(rangeEntry.getValue(), granularity);
            // this mappedRange may exist range overlap
            timePointSet.add(mappedRange.getLowerDateTime());
            timePointSet.add(mappedRange.getUpperDateTime());
        }
        List<LocalDateTime> timePointList = Lists.newArrayList(timePointSet);
        // deal overlap
        Map<String, Range<PartitionKey>> result = Maps.newHashMap();
        if (timePointList.size() < 2) {
            return result;
        }
        for (int i = 1; i < timePointList.size(); i++) {
            try {
                PartitionKey lowerPartitionKey = new PartitionKey();
                LocalDateTime lowerDateTime = timePointList.get(i - 1);
                LocalDateTime upperDateTime = timePointList.get(i);
                PartitionKey upperPartitionKey = new PartitionKey();
                if (partitionType == PrimitiveType.DATE) {
                    lowerPartitionKey.pushColumn(new DateLiteral(lowerDateTime, Type.DATE), partitionType);
                    upperPartitionKey.pushColumn(new DateLiteral(upperDateTime, Type.DATE), partitionType);
                } else {
                    lowerPartitionKey.pushColumn(new DateLiteral(lowerDateTime, Type.DATETIME), partitionType);
                    upperPartitionKey.pushColumn(new DateLiteral(upperDateTime, Type.DATETIME), partitionType);
                }
                String mvPartitionName = getMVPartitionName(lowerDateTime, upperDateTime, granularity);
                result.put(mvPartitionName, Range.closedOpen(lowerPartitionKey, upperPartitionKey));
            } catch (AnalysisException ex) {
                throw new SemanticException("Convert to DateLiteral failed:", ex);
            }
        }
        return result;
    }

    public static PartitionMapping mappingRange(Range<PartitionKey> baseRange, String granularity) {
        // assume expr partition must be DateLiteral and only one partition
        LiteralExpr lowerExpr = baseRange.lowerEndpoint().getKeys().get(0);
        LiteralExpr upperExpr = baseRange.upperEndpoint().getKeys().get(0);
        Preconditions.checkArgument(lowerExpr instanceof DateLiteral);
        Preconditions.checkArgument(upperExpr instanceof DateLiteral);
        DateLiteral lowerDate = (DateLiteral) lowerExpr;
        DateLiteral upperDate = (DateLiteral) upperExpr;
        LocalDateTime lowerDateTime = lowerDate.toLocalDateTime();
        LocalDateTime upperDateTime = upperDate.toLocalDateTime();

        LocalDateTime truncLowerDateTime = getLowerDateTime(lowerDateTime, granularity);
        LocalDateTime truncUpperDateTime = getUpperDateTime(upperDateTime, granularity);
        return new PartitionMapping(truncLowerDateTime, truncUpperDateTime);
    }

    public static Map<String, Set<String>> generatePartitionRefMap(Map<String, Range<PartitionKey>> srcRangeMap,
                                                             Map<String, Range<PartitionKey>> dstRangeMap) {
        Map<String, Set<String>> result = Maps.newHashMap();
        for (Map.Entry<String, Range<PartitionKey>> srcEntry : srcRangeMap.entrySet()) {
            Iterator<Map.Entry<String, Range<PartitionKey>>> dstIter = dstRangeMap.entrySet().iterator();
            result.put(srcEntry.getKey(), Sets.newHashSet());
            while (dstIter.hasNext()) {
                Map.Entry<String, Range<PartitionKey>> dstEntry = dstIter.next();
                Range<PartitionKey> dstRange = dstEntry.getValue();
                int upperLowerCmp = srcEntry.getValue().upperEndpoint().compareTo(dstRange.lowerEndpoint());
                if (upperLowerCmp <= 0) {
                    continue;
                }
                int lowerUpperCmp = srcEntry.getValue().lowerEndpoint().compareTo(dstRange.upperEndpoint());
                if (lowerUpperCmp >= 0) {
                    continue;
                }
                Set<String> dstNames = result.get(srcEntry.getKey());
                dstNames.add(dstEntry.getKey());
            }
        }
        return result;
    }

    public static void calcPotentialRefreshPartition(Set<String> needRefreshMvPartitionNames,
                                                      Set<String> baseChangedPartitionNames,
                                                      Map<String, Set<String>> baseToMvNameRef,
                                                      Map<String, Set<String>> mvToBaseNameRef) {
        int curNameCount = needRefreshMvPartitionNames.size();
        int updatedCount = SyncPartitionUtils.gatherPotentialRefreshPartitionNames(baseChangedPartitionNames,
                needRefreshMvPartitionNames, baseToMvNameRef, mvToBaseNameRef);
        while (curNameCount != updatedCount) {
            curNameCount = updatedCount;
            updatedCount = SyncPartitionUtils.gatherPotentialRefreshPartitionNames(baseChangedPartitionNames,
                    needRefreshMvPartitionNames, baseToMvNameRef, mvToBaseNameRef);
        }
    }
    private static int gatherPotentialRefreshPartitionNames(Set<String> baseChangedPartitionNames,
                                                     Set<String> needRefreshMvPartitionNames,
                                                     Map<String, Set<String>> baseToMvNameRef,
                                                     Map<String, Set<String>> mvToBaseNameRef) {
        for (String needRefreshMvPartitionName : needRefreshMvPartitionNames) {
            Set<String> baseNames = mvToBaseNameRef.get(needRefreshMvPartitionName);
            baseChangedPartitionNames.addAll(baseNames);
            for (String baseName : baseNames) {
                Set<String> mvNames = baseToMvNameRef.get(baseName);
                needRefreshMvPartitionNames.addAll(mvNames);
            }
        }
        return needRefreshMvPartitionNames.size();
    }

    public static String getMVPartitionName(LocalDateTime lowerDateTime, LocalDateTime upperDateTime,
                                            String granularity) {
        switch (granularity) {
            case MINUTE:
                return DEFAULT_PREFIX + lowerDateTime.format(DateUtils.MINUTE_FORMATTER) +
                        "_" + upperDateTime.format(DateUtils.MINUTE_FORMATTER);
            case HOUR:
                return DEFAULT_PREFIX + lowerDateTime.format(DateUtils.HOUR_FORMATTER) +
                        "_" + upperDateTime.format(DateUtils.HOUR_FORMATTER);
            case DAY:
                return DEFAULT_PREFIX + lowerDateTime.format(DateUtils.DATEKEY_FORMATTER) +
                        "_" + upperDateTime.format(DateUtils.DATEKEY_FORMATTER);
            case MONTH:
                return DEFAULT_PREFIX + lowerDateTime.format(DateUtils.MONTH_FORMATTER) +
                        "_" + upperDateTime.format(DateUtils.MONTH_FORMATTER);
            case QUARTER:
                return DEFAULT_PREFIX + lowerDateTime.format(DateUtils.QUARTER_FORMATTER) +
                        "_" + upperDateTime.format(DateUtils.QUARTER_FORMATTER);
            case YEAR:
                return DEFAULT_PREFIX + lowerDateTime.format(DateUtils.YEAR_FORMATTER) +
                        "_" + upperDateTime.format(DateUtils.YEAR_FORMATTER);
            default:
                throw new SemanticException("Do not support date_trunc format string:{}", granularity);
        }
    }

    // when the upperDateTime is the same as granularity rollup time, should not +1
    @NotNull
    private static LocalDateTime getUpperDateTime(LocalDateTime upperDateTime, String granularity) {
        LocalDateTime truncUpperDateTime;
        switch (granularity) {
            case MINUTE:
                if (upperDateTime.withNano(0).withSecond(0).equals(upperDateTime)) {
                    truncUpperDateTime = upperDateTime;
                } else {
                    truncUpperDateTime = upperDateTime.plusMinutes(1).withNano(0).withSecond(0);
                }
                break;
            case HOUR:
                if (upperDateTime.withNano(0).withSecond(0).withMinute(0).equals(upperDateTime)) {
                    truncUpperDateTime = upperDateTime;
                } else {
                    truncUpperDateTime = upperDateTime.plusHours(1).withNano(0).withSecond(0).withMinute(0);
                }
                break;
            case DAY:
                if (upperDateTime.with(LocalTime.MIN).equals(upperDateTime)) {
                    truncUpperDateTime = upperDateTime;
                } else {
                    truncUpperDateTime = upperDateTime.plusDays(1).with(LocalTime.MIN);
                }
                break;
            case MONTH:
                if (upperDateTime.with(TemporalAdjusters.firstDayOfMonth()).equals(upperDateTime)) {
                    truncUpperDateTime = upperDateTime;
                } else {
                    truncUpperDateTime = upperDateTime.plusMonths(1).with(TemporalAdjusters.firstDayOfMonth());
                }
                break;
            case QUARTER:
                if (upperDateTime.with(upperDateTime.getMonth().firstMonthOfQuarter())
                        .with(TemporalAdjusters.firstDayOfMonth()).equals(upperDateTime)) {
                    truncUpperDateTime = upperDateTime;
                } else {
                    LocalDateTime nextDateTime = upperDateTime.plusMonths(3);
                    truncUpperDateTime = nextDateTime.with(nextDateTime.getMonth().firstMonthOfQuarter())
                            .with(TemporalAdjusters.firstDayOfMonth());
                }
                break;
            case YEAR:
                if (upperDateTime.with(TemporalAdjusters.firstDayOfYear()).equals(upperDateTime)) {
                    truncUpperDateTime = upperDateTime;
                } else {
                    truncUpperDateTime = upperDateTime.plusYears(1).with(TemporalAdjusters.firstDayOfYear());
                }
                break;
            default:
                throw new SemanticException("Do not support date_trunc format string:{}", granularity);
        }
        return truncUpperDateTime;
    }

    private static LocalDateTime getLowerDateTime(LocalDateTime lowerDateTime, String granularity) {
        LocalDateTime truncLowerDateTime;
        switch (granularity) {
            case MINUTE:
                truncLowerDateTime = lowerDateTime.withNano(0).withSecond(0);
                break;
            case HOUR:
                truncLowerDateTime = lowerDateTime.withNano(0).withSecond(0).withMinute(0);
                break;
            case DAY:
                truncLowerDateTime = lowerDateTime.with(LocalTime.MIN);
                break;
            case MONTH:
                truncLowerDateTime = lowerDateTime.with(TemporalAdjusters.firstDayOfMonth());
                break;
            case QUARTER:
                truncLowerDateTime = lowerDateTime.with(lowerDateTime.getMonth().firstMonthOfQuarter())
                        .with(TemporalAdjusters.firstDayOfMonth());
                break;
            case YEAR:
                truncLowerDateTime = lowerDateTime.with(TemporalAdjusters.firstDayOfYear());
                break;
            default:
                throw new SemanticException("Do not support in date_trunc format string:" + granularity);
        }
        return truncLowerDateTime;
    }

    public static Map<String, Range<PartitionKey>> diffRange(Map<String, Range<PartitionKey>> srcRangeMap,
                                                      Map<String, Range<PartitionKey>> dstRangeMap) {

        Map<String, Range<PartitionKey>> result = Maps.newHashMap();

        LinkedHashMap<String, Range<PartitionKey>> srcRangeLinkMap = srcRangeMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(RangeUtils.RANGE_COMPARATOR))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
        LinkedHashMap<String, Range<PartitionKey>> dstRangeLinkMap = dstRangeMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(RangeUtils.RANGE_COMPARATOR))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));

        for (Map.Entry<String, Range<PartitionKey>> srcEntry : srcRangeLinkMap.entrySet()) {
            boolean found = false;
            Iterator<Range<PartitionKey>> dstIter = dstRangeLinkMap.values().iterator();
            while (dstIter.hasNext()) {
                Range<PartitionKey> dstRange = dstIter.next();
                int lowerCmp = srcEntry.getValue().lowerEndpoint().compareTo(dstRange.lowerEndpoint());
                int upperCmp = srcEntry.getValue().upperEndpoint().compareTo(dstRange.upperEndpoint());
                // must be same range
                if (lowerCmp == 0 && upperCmp == 0) {
                    dstIter.remove();
                    found = true;
                    break;
                }
            }
            if (!found) {
                result.put(srcEntry.getKey(), srcEntry.getValue());
            }
        }
        return result;
    }

}
