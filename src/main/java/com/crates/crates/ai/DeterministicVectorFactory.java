package com.crates.crates.ai;

import java.util.SplittableRandom;

/**
 * 같은 seed에는 항상 같은 값이 나오는 float32 임베딩 벡터 생성기.
 *
 * <p>좋아요가 없는 사용자의 시작 취향 벡터를 만든다(UserVectorService). Cosine 거리는 0 벡터에
 * 정의되지 않아 무언가는 넣어야 하는데, (사용자, 날짜)마다 같은 값이 나와야 추천이 요청마다
 * 흔들리지 않는다.</p>
 *
 * <p>Java의 {@code float}이 곧 float32라 유효 숫자는 약 7자리이며,
 * Cosine 거리 검색에 바로 넣을 수 있도록 마지막에 L2 정규화를 적용한다.</p>
 */
public final class DeterministicVectorFactory {

    private DeterministicVectorFactory() {
    }

    public static float[] create(long seed, int dimension)
    {
        if (dimension <= 0)
        {
            throw new IllegalArgumentException("벡터 차원은 1 이상이어야 합니다: " + dimension);
        }

        SplittableRandom random = new SplittableRandom(seed);
        float[] vector = new float[dimension];
        double squaredSum = 0.0;

        for (int index = 0; index < dimension; index++)
        {
            float value = (float) random.nextDouble(-1.0, 1.0);
            vector[index] = value;
            squaredSum += (double) value * value;
        }

        float norm = (float) Math.sqrt(squaredSum);
        for (int index = 0; index < dimension; index++)
        {
            vector[index] = vector[index] / norm;
        }
        return vector;
    }
}
