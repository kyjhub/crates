package com.crates.crates.seed;

import org.springframework.core.io.Resource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * AI 서버가 내려준 콘텐츠 벡터 CSV({@code id,vector_b64,model_version})를 읽는다.
 *
 * <p>파일이 수백 MB라(book만 440MB) 통째로 올리지 않고 한 줄씩 흘려보낸다.
 * 세 컬럼 모두 따옴표·쉼표가 들어갈 수 없는 값(id, base64, 버전 문자열)이라
 * CSV 파서 없이 앞에서부터 쉼표 두 개로 자른다.</p>
 */
public final class ContentVectorCsv {

    private static final String HEADER = "id,vector_b64,model_version";

    private ContentVectorCsv() {
    }

    /** CSV 한 줄. vectorBase64는 아직 디코딩하지 않은 값이다 — id만 필요한 쪽이 비용을 치르지 않게. */
    public record Row(String sourceKey, String vectorBase64, String modelVersion) {
    }

    public static void forEachRow(Resource file, Consumer<Row> action) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            requireHeader(reader.readLine(), file);

            String line;
            long lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                action.accept(parse(line, file, lineNumber));
            }
        }
    }

    /** 첫 데이터 행. 파일 전체를 읽지 않고 모델 버전만 확인할 때 쓴다. */
    public static Optional<Row> firstRow(Resource file) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            requireHeader(reader.readLine(), file);
            String line = reader.readLine();
            return line == null || line.isBlank() ? Optional.empty() : Optional.of(parse(line, file, 2));
        }
    }

    /**
     * base64로 인코딩된 float32 little-endian 배열을 푼다.
     *
     * <p>AI 서버(numpy)의 기본 바이트 순서가 little-endian이다. Java의 기본값은 big-endian이라
     * 순서를 지정하지 않으면 예외 없이 엉뚱한 숫자가 나온다.</p>
     */
    public static float[] decode(String vectorBase64, int dimension) {
        byte[] bytes = Base64.getDecoder().decode(vectorBase64);
        if (bytes.length != dimension * Float.BYTES) {
            throw new IllegalStateException(
                    "벡터 길이가 차원과 맞지 않습니다. expected=" + dimension * Float.BYTES
                            + " bytes, actual=" + bytes.length + " bytes");
        }

        float[] vector = new float[dimension];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vector);
        return vector;
    }

    private static void requireHeader(String header, Resource file) {
        if (!HEADER.equals(header)) {
            // 컬럼 순서가 바뀐 파일을 그대로 읽으면 base64를 id로 쓰는 식으로 조용히 틀린다.
            throw new IllegalStateException(
                    "벡터 CSV 헤더가 예상과 다릅니다. file=" + file.getDescription()
                            + ", expected=" + HEADER + ", actual=" + header);
        }
    }

    private static Row parse(String line, Resource file, long lineNumber) {
        int first = line.indexOf(',');
        int second = first < 0 ? -1 : line.indexOf(',', first + 1);
        if (first <= 0 || second < 0 || second == line.length() - 1) {
            throw new IllegalStateException(
                    "벡터 CSV 형식이 잘못됐습니다. file=" + file.getDescription() + ", line=" + lineNumber);
        }
        return new Row(line.substring(0, first), line.substring(first + 1, second), line.substring(second + 1));
    }
}
