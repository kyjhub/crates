package com.crates.crates.ai;

import java.util.SplittableRandom;

/**
 * 같은 seed에는 항상 같은 값이 나오는 float32 임베딩 벡터 생성기.
 *
 * <p>실제 임베딩을 구할 수 없는 로컬 환경에서 Qdrant에 심는 콘텐츠 벡터와
 * 검색어 벡터가 <b>같은 규칙</b>으로 만들어져야 Cosine 검색이 의미를 갖기 때문에
 * 생성 로직을 한 곳에 모아둔다.</p>
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

    /** 서로 가까운 seed 값도 충분히 다른 난수 시퀀스를 갖도록 64비트 값을 섞는다. */
    public static long mix64(long value)
    {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
