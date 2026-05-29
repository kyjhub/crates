# Elasticsearch 공식 이미지 사용 (8.x 최신 안정 버전)
FROM docker.elastic.co/elasticsearch/elasticsearch:8.11.1

# 한국어 형태소 분석기 Nori 플러그인 설치
RUN elasticsearch-plugin install analysis-nori

# (선택) 추가적인 사전 설정이 필요하다면 여기에 작성