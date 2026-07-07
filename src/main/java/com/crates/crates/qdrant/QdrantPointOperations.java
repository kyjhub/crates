package com.crates.crates.qdrant;

import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Component
@RequiredArgsConstructor
public class QdrantPointOperations {

    private final QdrantClient qdrantClient;

    public void upsertPoint(String collectionName, long id, float[] vector, Map<String, Object> payload) {
        upsertPoints(collectionName, List.of(new PointRecord(id, vector, payload)));
    }

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
