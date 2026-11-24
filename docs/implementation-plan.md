# 구현 계획

| 단계 | 완료 조건 |
|---|---|
| P00 | Gradle Wrapper와 JVM/Android 모듈 초기 빌드, SDK·검증 환경 확인 |
| P01 | 공용 fixture 원본 및 독립 기대값, 스키마·도로·가상 시계 검증 |
| P02 | 최초 관측 판정·reason code·시각 경계 검증 |
| P03 | 도로 매칭의 동률·누락·분기·모호성 검증 |
| P04 | 정합 DR 기준점, 신호 복구 타이머, 이탈 및 복귀 검증 |
| P05 | S01~S17 결정적 재생 및 지표 산출, JVM CLI |
| P06 | MVVM 화면, 재생·전환·로그·결과, Job 수명 검증 |
| P07 | JVM·Android 단위/계측, Lint, 빌드·Manifest, CI·스트레스 |
| P08 | 전체 재실행 및 보고서·데모·APK·식별 정보 검수 |

compile/target SDK 36, min SDK 26, JDK 17, AGP 8.13.2, Gradle 8.13을 초기 조합으로 고정했다. P01에서 독립 기대 결과를 고정한 후 엔진을 구현한다.
