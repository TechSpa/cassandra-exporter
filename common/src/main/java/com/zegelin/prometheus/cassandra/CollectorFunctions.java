package com.zegelin.prometheus.cassandra;

import com.google.common.collect.Maps;
import com.zegelin.prometheus.cassandra.collector.dynamic.FunctionalMetricFamilyCollector.CollectorFunction;
import com.zegelin.prometheus.cassandra.collector.dynamic.FunctionalMetricFamilyCollector.LabeledObjectGroup;
import com.zegelin.prometheus.domain.*;
import org.apache.cassandra.metrics.CassandraMetricsRegistry.JmxCounterMBean;
import org.apache.cassandra.metrics.CassandraMetricsRegistry.JmxGaugeMBean;
import org.apache.cassandra.metrics.CassandraMetricsRegistry.JmxMeterMBean;
import org.apache.cassandra.utils.EstimatedHistogram;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

public final class CollectorFunctions {
    private CollectorFunctions() {}

    private static Stream<NumericMetric> counterMetricsStream(final LabeledObjectGroup<JmxCounterMBean> group, final Function<Float, Float> scaleFunction) {
        return group.labeledObjects().entrySet().stream()
                .map(e -> new Object() {
                    final Labels labels = e.getKey();
                    final JmxCounterMBean counter = e.getValue();
                })
                .map(e -> new NumericMetric(e.labels, scaleFunction.apply((float) e.counter.getCount())));
    }

    /**
     * Collect a {@link JmxCounterMBean} as a Prometheus counter
     */
    public static CollectorFunction<JmxCounterMBean> counterAsCounter(final Function<Float, Float> scaleFunction) {
        return group -> {
            final Stream<NumericMetric> metricStream = counterMetricsStream(group, scaleFunction);

            return Stream.of(new CounterMetricFamily(group.name(), group.help(), metricStream));
        };
    }

    public static CollectorFunction<JmxCounterMBean> counterAsCounter() {
        return counterAsCounter(l -> l);
    }


    /**
     * Collect a {@link JmxCounterMBean} as a Prometheus gauge
     */
    public static CollectorFunction<JmxCounterMBean> counterAsGauge(final Function<Float, Float> scaleFunction) {
        return group -> {
            final Stream<NumericMetric> metricStream = counterMetricsStream(group, scaleFunction);

            return Stream.of(new GaugeMetricFamily(group.name(), group.help(), metricStream));
        };
    }

    public static CollectorFunction<JmxCounterMBean> counterAsGauge() {
        return counterAsGauge(Function.identity());
    }



    /**
     * Collect a {@link JmxMeterMBean} as a Prometheus counter
     */
    public static CollectorFunction<JmxMeterMBean> meterAsCounter(final Function<Float, Float> scaleFunction) {
        return group -> {
            final Stream<NumericMetric> metricStream = group.labeledObjects().entrySet().stream()
                    .map(e -> new Object() {
                        final Labels labels = e.getKey();
                        final JmxMeterMBean meter = e.getValue();
                    })
                    .map(e -> new NumericMetric(e.labels, scaleFunction.apply((float) e.meter.getCount())));


            return Stream.of(new CounterMetricFamily(group.name(), group.help(), metricStream));
        };
    }

    public static CollectorFunction<JmxMeterMBean> meterAsCounter() {
        return meterAsCounter(Function.identity());
    }


    private static Stream<NumericMetric> numericGaugeMetricsStream(final LabeledObjectGroup<JmxGaugeMBean> group, final Function<Float, Float> scaleFunction) {
        return group.labeledObjects().entrySet().stream()
                .map(e -> new Object() {
                    final Labels labels = e.getKey();
                    final JmxGaugeMBean gauge = e.getValue();
                })
                .map(e -> new NumericMetric(e.labels, scaleFunction.apply(((Number) e.gauge.getValue()).floatValue())));
    }

    /**
     * Collect a {@link JmxGaugeMBean} with a {@link Number} value as a Prometheus gauge
     */
    public static CollectorFunction<JmxGaugeMBean> numericGaugeAsGauge(final Function<Float, Float> scaleFunction) {
        return group -> {
            final Stream<NumericMetric> metricStream = numericGaugeMetricsStream(group, scaleFunction);

            return Stream.of(new GaugeMetricFamily(group.name(), group.help(), metricStream));
        };
    }

    public static CollectorFunction<JmxGaugeMBean> numericGaugeAsGauge() {
        return numericGaugeAsGauge(Function.identity());
    }


    /**
     * Collect a {@link JmxGaugeMBean} with a {@see Number} value as a Prometheus counter
     */
    public static CollectorFunction<JmxGaugeMBean> numericGaugeAsCounter(final Function<Float, Float> scaleFunction) {
        return group -> {
            final Stream<NumericMetric> metricStream = numericGaugeMetricsStream(group, scaleFunction);

            return Stream.of(new CounterMetricFamily(group.name(), group.help(), metricStream));
        };
    }

    public static CollectorFunction<JmxGaugeMBean> numericGaugeAsCounter() {
        return numericGaugeAsCounter(Function.identity());
    }



    /**
     * Collect a {@link JmxGaugeMBean} with a Cassandra {@link EstimatedHistogram} value as a Prometheus summary
     */
    public static CollectorFunction<JmxGaugeMBean> histogramGaugeAsSummary(final Function<Float, Float> quantileScaleFunction) {
        return group -> {
            final Stream<SummaryMetricFamily.Summary> summaryStream = group.labeledObjects().entrySet().stream()
                    .map(e -> new Object() {
                        final Labels labels = e.getKey();
                        final JmxGaugeMBean gauge = e.getValue();
                    })
                    .map(e -> {
                        final long[] bucketData = (long[]) e.gauge.getValue();

                        if (bucketData.length == 0) {
                            return new SummaryMetricFamily.Summary(e.labels, Float.NaN, Float.NaN, Maps.toMap(Quantile.STANDARD_QUANTILES, q -> Float.NaN));
                        }

                        final EstimatedHistogram histogram = new EstimatedHistogram(bucketData);

                        final Map<Quantile, Float> quantiles = Maps.toMap(Quantile.STANDARD_QUANTILES, q -> quantileScaleFunction.apply((float) histogram.percentile(q.value)));

                        return new SummaryMetricFamily.Summary(e.labels, Float.NaN, histogram.count(), quantiles);
                    });

            return Stream.of(new SummaryMetricFamily(group.name(), group.help(), summaryStream));
        };
    }

    public static CollectorFunction<JmxGaugeMBean> histogramGaugeAsSummary() {
        return histogramGaugeAsSummary(l -> l);
    }

    /**
     * Collect a {@link SamplingCounting} as a Prometheus summary
     */
    protected static CollectorFunction<SamplingCounting> samplingAndCountingAsSummary(final Function<Float, Float> quantileScaleFunction) {
        return group -> {
            final Stream<SummaryMetricFamily.Summary> summaryStream = group.labeledObjects().entrySet().stream()
                    .map(e -> new Object() {
                        final Labels labels = e.getKey();
                        final SamplingCounting samplingCounting = e.getValue();
                    })
                    .map(e -> {
                        final Map<Quantile, Float> quantiles = Maps.transformValues(e.samplingCounting.getQuantiles(), v -> quantileScaleFunction.apply(v.floatValue()));

                        return new SummaryMetricFamily.Summary(e.labels, Float.NaN, e.samplingCounting.getCount(), quantiles);
                    });

            return Stream.of(new SummaryMetricFamily(group.name(), group.help(), summaryStream));
        };
    }

    public static CollectorFunction<SamplingCounting> samplingAndCountingAsSummary() {
        return samplingAndCountingAsSummary(Function.identity());
    }
}
