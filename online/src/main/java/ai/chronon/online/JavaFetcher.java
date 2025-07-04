/*
 *    Copyright (C) 2023 The Chronon Authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package ai.chronon.online;

import ai.chronon.online.Fetcher.Request;
import ai.chronon.online.Fetcher.Response;
import ai.chronon.online.FutureConverters;

import scala.Some;
import scala.collection.Iterator;
import scala.collection.Seq;
import scala.Option;
import scala.collection.mutable.ArrayBuffer;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class JavaFetcher {
    Fetcher fetcher;

    public JavaFetcher(KVStore kvStore, String metaDataSet, Long timeoutMillis, Consumer<LoggableResponse> logFunc, ExternalSourceRegistry registry, String callerName, Boolean disableErrorThrows) {
        this.fetcher = new Fetcher(kvStore, metaDataSet, timeoutMillis, logFunc, false, registry, callerName, null, disableErrorThrows, null, Option.apply(32));
    }

    public JavaFetcher(KVStore kvStore, String metaDataSet, Long timeoutMillis, Consumer<LoggableResponse> logFunc, ExternalSourceRegistry registry) {
        this.fetcher = new Fetcher(kvStore, metaDataSet, timeoutMillis, logFunc, false, registry, null, null, false, null, Option.apply(32));
    }

    public JavaFetcher(KVStore kvStore, String metaDataSet, Long timeoutMillis, Consumer<LoggableResponse> logFunc, ExternalSourceRegistry registry, String callerName, FlagStore flagStore, Boolean disableErrorThrows) {
        this.fetcher = new Fetcher(kvStore, metaDataSet, timeoutMillis, logFunc, false, registry, callerName, flagStore, disableErrorThrows, null, Option.apply(32));
    }

    /* user builder pattern to create JavaFetcher
    example way to create the java fetcher
    JavaFetcher fetcher = new JavaFetcher.Builder(kvStore, metaDataSet, timeoutMillis, logFunc, registry)
                                        .callerName(callerName)
                                        .flagStore(flagStore)
                                        .disableErrorThrows(disableErrorThrows)
                                        .build();
     */
    private JavaFetcher(Builder builder) {
        this.fetcher = new Fetcher(builder.kvStore,
                builder.metaDataSet,
                builder.timeoutMillis,
                builder.logFunc,
                builder.debug,
                builder.registry,
                builder.callerName,
                builder.flagStore,
                builder.disableErrorThrows,
                builder.executionContextOverride,
                builder.joinFetchParallelChunkSize.isPresent() ? Option.apply(builder.joinFetchParallelChunkSize.get()) : Option.empty());
    }

    public static class Builder {
        private KVStore kvStore;
        private String metaDataSet;
        private Long timeoutMillis;
        private Consumer<LoggableResponse> logFunc;
        private ExternalSourceRegistry registry;
        private String callerName;
        private Boolean debug;
        private FlagStore flagStore;
        private Boolean disableErrorThrows;
        private ExecutionContext executionContextOverride;

        private Optional<Integer> joinFetchParallelChunkSize = Optional.empty();

        public Builder(KVStore kvStore, String metaDataSet, Long timeoutMillis,
                       Consumer<LoggableResponse> logFunc, ExternalSourceRegistry registry) {
            this.kvStore = kvStore;
            this.metaDataSet = metaDataSet;
            this.timeoutMillis = timeoutMillis;
            this.logFunc = logFunc;
            this.registry = registry;
        }

        public Builder callerName(String callerName) {
            this.callerName = callerName;
            return this;
        }

        public Builder flagStore(FlagStore flagStore) {
            this.flagStore = flagStore;
            return this;
        }

        public Builder disableErrorThrows(Boolean disableErrorThrows) {
            this.disableErrorThrows = disableErrorThrows;
            return this;
        }

        public Builder debug(Boolean debug) {
            this.debug = debug;
            return this;
        }

        public Builder executionContextOverride(ExecutionContext executionContextOverride) {
            this.executionContextOverride = executionContextOverride;
            return this;
        }

        public Builder joinFetchParallelChunkSize(Integer joinFetchParallelChunkSize) {
            this.joinFetchParallelChunkSize = Optional.ofNullable(joinFetchParallelChunkSize);
            return this;
        }

        public JavaFetcher build() {
            return new JavaFetcher(this);
        }
    }


    public static List<JavaResponse> toJavaResponses(Seq<Response> responseSeq) {
        List<JavaResponse> result = new ArrayList<>(responseSeq.size());
        Iterator<Response> it = responseSeq.iterator();
        while (it.hasNext()) {
            result.add(new JavaResponse(it.next()));
        }
        return result;
    }

    private CompletableFuture<List<JavaResponse>> convertResponsesWithTs(Future<FetcherResponseWithTs> responses, boolean isGroupBy, long startTs) {
        return FutureConverters.toJava(responses).toCompletableFuture().thenApply(resps -> {
            List<JavaResponse> jResps = toJavaResponses(resps.responses());
            List<String> requestNames = jResps.stream().map(jResp -> jResp.request.name).collect(Collectors.toList());
            instrument(requestNames, isGroupBy, "java.response_conversion.latency.millis", resps.endTs());
            instrument(requestNames, isGroupBy, "java.overall.latency.millis", startTs);
            return jResps;
        });
    }

    public static List<JavaStatsResponse> toJavaStatsResponses(Seq<Fetcher.StatsResponse> responseSeq) {
        List<JavaStatsResponse> result = new ArrayList<>(responseSeq.size());
        Iterator<Fetcher.StatsResponse> it = responseSeq.iterator();
        while (it.hasNext()) {
            result.add(toJavaStatsResponse(it.next()));
        }
        return result;
    }

    public static JavaStatsResponse toJavaStatsResponse(Fetcher.StatsResponse response) {
        return new JavaStatsResponse(response);
    }

    public static JavaSeriesStatsResponse toJavaSeriesStatsResponse(Fetcher.SeriesStatsResponse response) {
        return new JavaSeriesStatsResponse(response);
    }

    private CompletableFuture<List<JavaStatsResponse>> convertStatsResponses(Future<Seq<Fetcher.StatsResponse>> responses) {
        return FutureConverters
                .toJava(responses)
                .toCompletableFuture()
                .thenApply(JavaFetcher::toJavaStatsResponses);
    }

    private Seq<Request> convertJavaRequestList(List<JavaRequest> requests, boolean isGroupBy, long startTs) {
        ArrayBuffer<Request> scalaRequests = new ArrayBuffer<>();
        for (JavaRequest request : requests) {
            Request convertedRequest = request.toScalaRequest();
            scalaRequests.$plus$eq(convertedRequest);
        }
        Seq<Request> scalaRequestsSeq = scalaRequests.toSeq();
        instrument(requests.stream().map(jReq -> jReq.name).collect(Collectors.toList()), isGroupBy, "java.request_conversion.latency.millis", startTs);
        return scalaRequestsSeq;
    }

    public CompletableFuture<List<JavaResponse>> fetchGroupBys(List<JavaRequest> requests) {
        long startTs = System.currentTimeMillis();
        // Convert java requests to scala requests
        Seq<Request> scalaRequests = convertJavaRequestList(requests, true, startTs);
        // Get responses from the fetcher
        Future<FetcherResponseWithTs> scalaResponses = this.fetcher.withTs(this.fetcher.fetchGroupBys(scalaRequests));
        // Convert responses to CompletableFuture
        return convertResponsesWithTs(scalaResponses, true, startTs);
    }

    public CompletableFuture<List<JavaResponse>> fetchJoin(List<JavaRequest> requests) {
        long startTs = System.currentTimeMillis();
        // Convert java requests to scala requests
        Seq<Request> scalaRequests = convertJavaRequestList(requests, false, startTs);
        // Get responses from the fetcher
        Future<FetcherResponseWithTs> scalaResponses = this.fetcher.withTs(this.fetcher.fetchJoin(scalaRequests, Option.empty()));
        // Convert responses to CompletableFuture
        return convertResponsesWithTs(scalaResponses, false, startTs);
    }

    public List<CompletableFuture<List<JavaResponse>>> fetchJoinChunked(List<JavaRequest> requests, int chunkSizeOverride) {
        long startTs = System.currentTimeMillis();
        // Convert java requests to scala requests
        Seq<Request> scalaRequests = convertJavaRequestList(requests, false, startTs);
        // Get responses from the fetcher
        Seq<Future<Seq<Response>>> scalaChunkedResponses = this.fetcher.fetchJoinChunked(scalaRequests, chunkSizeOverride, Option.empty());
        // Convert responses to CompletableFuture
        List<CompletableFuture<List<JavaResponse>>> futures = new ArrayList<>(scalaChunkedResponses.size());
        Iterator<Future<Seq<Response>>> it = scalaChunkedResponses.iterator();
        while (it.hasNext()) {
            Future<FetcherResponseWithTs> scalaResponse = this.fetcher.withTs(it.next());
            CompletableFuture<List<JavaResponse>> javaResponse = convertResponsesWithTs(scalaResponse, false, startTs);
            futures.add(javaResponse);
        }
        return futures;
    }

    private void instrument(List<String> requestNames, boolean isGroupBy, String metricName, Long startTs) {
        long endTs = System.currentTimeMillis();
        for (String s : requestNames) {
            Metrics.Context ctx;
            if (isGroupBy) {
                ctx = getGroupByContext(s);
            } else {
                ctx = getJoinContext(s);
            }
            ctx.distribution(metricName, endTs - startTs);
        }
    }

    private Metrics.Context getJoinContext(String joinName) {
        return new Metrics.Context("join.fetch", joinName, null, null, false, null, null, null, null);
    }

    private Metrics.Context getGroupByContext(String groupByName) {
        return new Metrics.Context("group_by.fetch", null, groupByName, null, false, null, null, null, null);
    }

    public CompletableFuture<JavaSeriesStatsResponse> fetchStatsTimeseries(JavaStatsRequest request) {
        Future<Fetcher.SeriesStatsResponse> response = this.fetcher.fetchStatsTimeseries(request.toScalaRequest());
        // Convert responses to CompletableFuture
        return FutureConverters.toJava(response).toCompletableFuture().thenApply(JavaFetcher::toJavaSeriesStatsResponse);
    }

    public CompletableFuture<JavaSeriesStatsResponse> fetchLogStatsTimeseries(JavaStatsRequest request) {
        Future<Fetcher.SeriesStatsResponse> response = this.fetcher.fetchLogStatsTimeseries(request.toScalaRequest());
        // Convert responses to CompletableFuture
        return FutureConverters.toJava(response).toCompletableFuture().thenApply(JavaFetcher::toJavaSeriesStatsResponse);
    }

    public CompletableFuture<JavaSeriesStatsResponse> fetchConsistencyMetricsTimeseries(JavaStatsRequest request) {
        Future<Fetcher.SeriesStatsResponse> response = this.fetcher.fetchConsistencyMetricsTimeseries(request.toScalaRequest());
        // Convert responses to CompletableFuture
        return FutureConverters.toJava(response).toCompletableFuture().thenApply(JavaFetcher::toJavaSeriesStatsResponse);
    }
}
