# Loopback 경로 신뢰성 실험

`:route-lab`은 JVM 모듈이다. `./gradlew :route-lab:test`가 임시 포트의 IPv4 loopback에 MockWebServer를 띄운다. 합성 O05 주행 OL13에서 관측된 기존 `REROUTE_RECOMMENDED`가 읽기 전용 `POST /route/lookup`을 시작한다. 고정된 대체 경로는 O05 공개 도로 ID와 허용 전이로 구성한다. 패키지 연결 상태는 `verifyModuleBoundaries`와 두 앱의 APK 검사에서 확인한다.

요청에는 스키마, 합성 세션과 요청 ID, 지도 ID와 SHA-256, 기존 경로 버전, 시작·목적지 edge ID를 넣는다. 응답은 요청·지도·체크섬·스키마·버전의 일치, 경로 길이, 시작과 목적지, 방향별 edge 연결 및 허용 전이를 검사한다. 성공 시 새 immutable 계획과 초기화한 엔진을 같은 잠금 안에서 반영한다. 중복 이탈은 outstanding 요청 하나로 합친다. 새 시작 edge, Reset, Stop과 지도 변경은 기존 호출을 취소하며 뒤늦게 전달된 응답은 현재 경로를 건드리지 못한다.

## 요청 정책

| 항목 | 값 |
|---|---|
| 목적지 | 숫자 IPv4 loopback, 동적 테스트 포트 |
| HTTP 제한 | 연결 2초, 읽기·쓰기 각 3초, 호출 5초 |
| 전체 기한과 시도 | 12초, 최초 포함 최대 3회 |
| 재시도 | 일시적 I/O·timeout과 408·429·502·503·504. 각 시도 바디는 1회 전송하고 클라이언트 자동 follow-up 및 redirect를 제한한다. |
| Backoff | 250ms·500ms + 0–99ms jitter. 유효한 Retry-After가 더 길면 이를 따르며 3초 상한 또는 전체 기한을 넘길 때 재시도하지 않는다. |
| 자원 | 동시에 실행하는 HTTP 1개, 응답 최대 32 KiB, 경로 최대 256 edge. 파서는 객체·스칼라와 단일 배열을 읽는다. 종료 시 호출·응답·연결 풀·디스패처와 테스트 서버를 정리한다. |

## 장애 검증

| ID | 관측 근거 |
|---|---|
| N01 | 합성 이탈 → HTTP 1회 → 버전 1→2, 이전 엔진 관측·기준점이 새 엔진에 없음을 확인 |
| N02 | 헤더·바디 지연 각각 timeout 1회, 기존 계획 유지 |
| N03 | 요청 후 연결 종료와 잘린 응답, 실제 요청 2회로 종료 |
| N04 | 503 및 Retry-After 0 → 성공 200, 동일 payload 2회, 반영 1회 |
| N05 | 429 및 Retry-After 1초, backoff 중 정지 후 요청 총 1회 |
| N06 | 400·401·403·404·307 각각 1회, redirect를 따라가지 않음 |
| N07 | 잘못된 JSON, 빈 응답, 타입·깊이 오류, 길이와 chunked 크기 초과 각각 1회 |
| N08 | 요청·지도·SHA·스키마·버전·경로 길이·끝점·도로 방향·허용 전이 오류 12건 거절 |
| N09 | 중복 20회 → 실제 HTTP 1회 |
| N10 | 지연 HTTP A 취소와 B 적용, 별도 fake에서 B가 대기할 때 A의 늦은 응답 폐기 |
| N11 | 지연 실제 HTTP와 취소가 불가능한 fake 각각에서 Reset·Stop·지도 변경 검증 |
| N12 | 가상 시간 backoff·기한, 실제 deadline에서 연결을 취소하고 실행·대기 호출 0 확인 |
| N13 | 성공 → 504 3회 후 기존 유효 계획 유지 → 명시적 요청으로 다음 버전 적용 |
| N14 | Live 위치 모듈과 Java HTTP 실험 간 의존 경계, Live APK DEX에 HTTP·fixture 서버 클래스가 없음을 확인 |
| N15 | 기본·Live APK의 권한과 DEX, 테스트 독립 실행, 기존 평가 원본 비교 |

N01–N13과 요청 취소 20회 반복은 JVM 자동 테스트 17건에 포함된다. N14–N15는 Gradle 모듈 및 실 APK 검증이다. 기록은 loopback 호출을 대상으로 하며 모바일 네트워크·실제 경로 서버 측정은 범위 밖이다.

2026-11-25에 OkHttp 4.12.0의 503, Retry-After 및 one-shot 처리 동작을 소스에서 확인했다. [OkHttp 재시도 구현](https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt)

[검증 결과](verification.md)
