# 기술 문서 안내

[프로젝트 README](../README.md) · [실제 앱 시연](demo.md) · [상세 아키텍처](architecture.md)

프로젝트의 설계 결정, 기술적 문제 해결, 검증 근거를 관심 영역별로 찾아볼 수 있습니다. 실제 구현 설명과 각 시점의 상세 측정·개발 이력을 구분합니다.

## 먼저 읽을 문서

| 관심 영역 | 문서 | 확인할 내용 |
|---|---|---|
| Android 아키텍처 | [시스템 아키텍처](architecture.md#전체-9개-모듈) | 9개 모듈의 Gradle 의존성, 순수 Kotlin 코어, MVVM·Compose, 실행 세대·취소 |
| 위치 기반 서비스 | [위치 알고리즘](algorithm.md) · [Live 위치 설계](live-location.md) | Map Matching, DR, 재획득·이탈, 위치 권한·FGS·리스너 해제 |
| 메모리 및 렌더링 | [저메모리 OOM 해결](stability-investigation.md#핵심-사례-저메모리-지도-oom) · [성능 최적화](refactor-performance.md) · [지도 렌더링 설계](korea-map-design.md) | Streaming parsing·Software Canvas, Spatial Grid·LOD·Culling·캐시 |
| 성능 분석 및 기술 판단 | [지도 전환 RCA](recovery-ui-switch-rca.md) · [렌더러 A/B와 후보 선택](rendering-memory-rca.md) | Macrobenchmark·Perfetto, 첫 표시와 관측 지연 분리, 채택 기준 |
| 장애 대응 | [Android 장애 분석](stability-investigation.md) · [Compose 계측 RCA](compose-slot-table-rca.md) | OOM·OS 종료 대응, Debug ANR·Crash 진단, 계측 동기화 |
| 네트워크 | [HTTP 신뢰성](network-reliability.md) | 독립 JVM loopback, timeout·retry·취소·응답 정합성 |
| 품질 자동화 | [검증 결과 및 재현](verification.md#최신-구현-검증-요약) | 단위 테스트, 평가 동등성, OS·접근성·CI, 재현 명령 |

## 데이터와 실행 화면

| 문서 | 내용 |
|---|---|
| [데모](demo.md) | 실제 앱의 터널 장애·재획득·이탈·복귀와 평가 화면 |
| [OSM 지도 갤러리](osm-visual-verification.md) | 국내 10개 지역과 Follow·Mid·Overview 화면 |
| [OSM 데이터 파이프라인](osm-data-pipeline.md) | PBF → 방향성 도로망·좌표계·표현 데이터, 체크섬·독립 평가 |
| [데이터 출처·라이선스](../data/osm/README.md) | OpenStreetMap/ODbL 표기 및 출처 |

## 정량 평가

가상 관측과 JVM 실행 결과입니다. 실제 도로 형상을 사용하더라도 가상 입력의 정확도는 실제 GNSS 정확도와 구분합니다.

| 문서 | 범위 |
|---|---|
| [기본 합성 회귀](evaluation-report.md) | v1 빠른 회귀 |
| [장시간 합성 평가](extended-evaluation-report.md) | L / DEMO_LONG / E |
| [OSM 평가](osm-evaluation-report.md) | 실제 도로망 위 가상 주행, 별도 OE 평가 |
| [OSM 고난도 평가](osm-challenge-report.md) | 별도 시드의 CE 8개, 정확도와 보류·복구 지표 |

## 분석 참고 자료 및 과거 이력

| 문서 | 범위 |
|---|---|
| [렌더링·메모리 분석 지표](rendering-memory-interview-notes.md) | TTID·TTFD·FrameTimeline·PSS·heap 분석 및 실험 설계 |
| [초기 OSM 성능](korea-map-performance.md) | 에뮬레이터 단회 비교 |
| [합성 지도·시나리오](map-and-scenarios.md) | OSM 이전 데이터·주행 계약 |
| [합성 지도 디자인](map-design.md) · [시각 검수](map-visual-verification.md) | OSM 이전 표현 개정과 검수 |
| [초기 구현 계획 이력](implementation-plan.md) | 단계별 구현·검증 계약 |

## 상세 검증 및 측정 기록

아래 문서는 환경·빌드·표본·실패 및 재시험까지 보존한 상세 기술 기록입니다. 검증 결과의 적용 범위와 최종 판정은 해당 원본을 따릅니다.

| 검증 영역 | 상세 기록 |
|---|---|
| UI 개편 및 검증 | [단계별 구현·회귀·검증](recovery-ui-validation.md) |
| 에뮬레이터 성능 | [지도 전환 및 주행 비교](recovery-ui-switch-rca.md) · [주행 최종 재시험](recovery-ui-emulator-g3-final-ride.md) |
| 실기기 검증 | [기기별 검증 범위](physical-device-validation.md) · [2026-11-25 실기기](real-device-final-validation.md) |
| 실기기 기동·프레임 | [본 측정](recovery-ui-physical-g7.md) · [최종 콜드 재시험](recovery-ui-physical-g7-final-cold.md) |
| 실기기 메모리·내구 | [최초 내구](recovery-ui-physical-g8.md) · [역순 재시험 및 분리 진단](recovery-ui-physical-g8-retest.md) |
| 실제 GPS·서비스 수명 | [GPS·서비스 검증](recovery-ui-physical-g9.md) · [후속 수명 시험](recovery-ui-physical-g9-followup.md) |
| 검증 판정 및 적용 범위 | [최종 검증 판정과 잔여 위험](recovery-ui-final-acceptance.md) |

각 실험의 기준 SHA와 측정 원본은 상세 문서 및 연결된 JSON에 보존합니다. [통합 검증](verification.md).
