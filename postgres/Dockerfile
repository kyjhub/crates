# 1. PostgreSQL 15 버전 공식 이미지 사용 (안정성이 높아 가장 많이 쓰입니다)
FROM postgres:15

# 2. 데이터베이스 서버의 시간대를 한국 시간(KST)으로 설정
ENV TZ=Asia/Seoul

# 3. 첫 기동 시 1회 실행되는 초기화 스크립트. 지금은 pg_stat_statements 확장 생성용이다.
#    데이터 디렉터리가 비어 있을 때만 돌기 때문에, 이미 만들어진 DB에는 적용되지 않는다.
#    그때는 psql로 직접 CREATE EXTENSION 하면 된다.
COPY ./init.sql /docker-entrypoint-initdb.d/