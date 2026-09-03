package com.crates.crates.qdrant;

import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

@Component
@RequiredArgsConstructor
public class QdrantPointOperations {

    private final QdrantClient qdrantClient;

    /**
     * 컬렉션이 없으면 지정한 차원과 Cosine 거리 방식으로 생성하고,
     * 이미 존재하면 현재 벡터 설정이 기대한 차원과 일치하는지 검증한다.
     */
    public void ensureCollection(String collectionName, int vectorDimension) {
        if (!await(qdrantClient.collectionExistsAsync(collectionName))) {
            Collections.VectorParams vectorParams = Collections.VectorParams.newBuilder()
                    .setSize(vectorDimension)
                    .setDistance(Collections.Distance.Cosine)
                    .build();
            await(qdrantClient.createCollectionAsync(collectionName, vectorParams));
        }

        Collections.CollectionInfo collectionInfo = await(qdrantClient.getCollectionInfoAsync(collectionName));
        Collections.VectorsConfig vectorsConfig = collectionInfo.getConfig()
                .getParams()
                .getVectorsConfig();

        if (vectorsConfig.getConfigCase() != Collections.VectorsConfig.ConfigCase.PARAMS) {
            throw new IllegalStateException("Qdrant collection must use a single unnamed vector: " + collectionName);
        }

        long actualDimension = vectorsConfig.getParams().getSize();
        if (actualDimension != vectorDimension) {
            throw new IllegalStateException(
                    "Qdrant vector dimension mismatch. collection=" + collectionName
                            + ", expected=" + vectorDimension
                            + ", actual=" + actualDimension
            );
        }
    }

    /** 컬렉션이 있으면 통째로 지운다. 없으면 아무 일도 하지 않는다. */
    public void deleteCollection(String collectionName) {
        if (await(qdrantClient.collectionExistsAsync(collectionName))) {
            await(qdrantClient.deleteCollectionAsync(collectionName));
        }
    }

    /**
     * 전달받은 관계형 content ID 중 Qdrant에 이미 저장된 point ID만 반환한다.
     * 기존의 실제 임베딩을 더미 벡터로 덮어쓰지 않기 위한 사전 조회에 사용한다.
     */
    public Set<Long> findExistingPointIds(String collectionName, List<Long> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }

        List<Points.PointId> pointIds = ids.stream()
                .map(PointIdFactory::id)
                .toList();
        List<Points.RetrievedPoint> points = await(
                qdrantClient.retrieveAsync(collectionName, pointIds, false, false, null)
        );

        Set<Long> existingIds = new HashSet<>(points.size());
        for (Points.RetrievedPoint point : points) {
            Points.PointId pointId = point.getId();
            if (pointId.getPointIdOptionsCase() != Points.PointId.PointIdOptionsCase.NUM) {
                throw new IllegalStateException("Qdrant point id is not numeric.");
            }
            existingIds.add(pointId.getNum());
        }
        return existingIds;
    }

    // 단일 벡터를 Qdrant point 형식으로 변환해 저장한다.
    public void upsertPoint(String collectionName, long id, float[] vector, Map<String, Object> payload) {
        upsertPoints(collectionName, List.of(new PointRecord(id, vector, payload)));
    }

    // 네트워크 호출 횟수를 줄이기 위해 여러 Qdrant point를 한 요청으로 저장한다.
    public void upsertPoints(String collectionName, List<PointRecord> points) {
        if (points.isEmpty()) {
            return;
        }

        List<Points.PointStruct> pointStructs = points.stream()
                .map(this::toPointStruct)
                .toList();

        await(qdrantClient.upsertAsync(collectionName, pointStructs));
    }

    public List<ScoredPointResult> search(String collectionName, float[] queryVector, int topK) {
        Points.SearchPoints request = Points.SearchPoints.newBuilder()
                .setCollectionName(collectionName)
                .addAllVector(toFloatList(queryVector))
                .setLimit(topK)
                .setWithPayload(WithPayloadSelectorFactory.enable(true))
                .build();

        return await(qdrantClient.searchAsync(request))
                .stream()
                .map(this::toResult)
                .toList();
    }

    private Points.PointStruct toPointStruct(PointRecord point) {
        return Points.PointStruct.newBuilder()
                .setId(PointIdFactory.id(point.id()))
                .setVectors(VectorsFactory.vectors(point.vector()))
                .putAllPayload(toQdrantPayload(point.payload()))
                .build();
    }

    private ScoredPointResult toResult(Points.ScoredPoint point) {
        return new ScoredPointResult(
                extractNumericId(point),
                point.getScore(),
                toJavaPayload(point.getPayloadMap())
        );
    }

    private long extractNumericId(Points.ScoredPoint point) {
        Points.PointId pointId = point.getId();
        if (pointId.getPointIdOptionsCase() != Points.PointId.PointIdOptionsCase.NUM) {
            throw new IllegalStateException("Qdrant point id is not numeric.");
        }
        return pointId.getNum();
    }

    private Map<String, JsonWithInt.Value> toQdrantPayload(Map<String, Object> payload) {
        Map<String, JsonWithInt.Value> result = new HashMap<>();
        payload.forEach((key, value) -> result.put(key, toQdrantValue(value)));
        return result;
    }

    private JsonWithInt.Value toQdrantValue(Object value) {
        if (value == null) {
            return ValueFactory.nullValue();
        }
        if (value instanceof String stringValue) {
            return ValueFactory.value(stringValue);
        }
        if (value instanceof Integer integerValue) {
            return ValueFactory.value(integerValue.longValue());
        }
        if (value instanceof Long longValue) {
            return ValueFactory.value(longValue);
        }
        if (value instanceof Float floatValue) {
            return ValueFactory.value(floatValue.doubleValue());
        }
        if (value instanceof Double doubleValue) {
            return ValueFactory.value(doubleValue);
        }
        if (value instanceof Boolean booleanValue) {
            return ValueFactory.value(booleanValue);
        }
        throw new IllegalArgumentException("Unsupported Qdrant payload value type: " + value.getClass().getName());
    }

    private Map<String, Object> toJavaPayload(Map<String, JsonWithInt.Value> payload) {
        Map<String, Object> result = new HashMap<>();
        payload.forEach((key, value) -> result.put(key, toJavaValue(value)));
        return result;
    }

    private Object toJavaValue(JsonWithInt.Value value) {
        return switch (value.getKindCase()) {
            case STRING_VALUE -> value.getStringValue();
            case INTEGER_VALUE -> value.getIntegerValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case BOOL_VALUE -> value.getBoolValue();
            case NULL_VALUE, KIND_NOT_SET -> null;
            case STRUCT_VALUE, LIST_VALUE -> value.toString();
        };
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> result = new java.util.ArrayList<>(vector.length);
        for (float value : vector) {
            result.add(value);
        }
        return result;
    }

    private <T> T await(com.google.common.util.concurrent.ListenableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant operation interrupted.", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Qdrant operation failed.", e.getCause());
        }
    }
}
