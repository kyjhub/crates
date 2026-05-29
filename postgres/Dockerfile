# 1. PostgreSQL 15 버전 공식 이미지 사용 (안정성이 높아 가장 많이 쓰입니다)
FROM postgres:15

# 2. 데이터베이스 서버의 시간대를 한국 시간(KST)으로 설정
ENV TZ=Asia/Seoul

# (선택) 3. 컨테이너가 처음 실행될 때 자동으로 테이블을 만들거나 초기 데이터를 넣고 싶다면,
# 로컬에 있는 init.sql 파일을 도커의 특정 폴더로 복사해두면 알아서 실행해 줍니다.
# COPY ./init.sql /docker-entrypoint-initdb.d/