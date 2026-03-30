<div align="center">

# ⚛ Integrated Science Nuclear Fission Simulator

**통합과학은 정말 최고의 과목이에요!**

*고성능 GPU 가속 2D 핵분열 시뮬레이터 — 실시간 수십만 입자, 60 FPS 보장*

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![LWJGL](https://img.shields.io/badge/LWJGL-3.3.3-orange?logo=opengl)](https://www.lwjgl.org)
[![OpenGL](https://img.shields.io/badge/OpenGL-4.3%2B-5586A4?logo=opengl)](https://www.opengl.org)
[![ImGui](https://img.shields.io/badge/ImGui-1.86-blueviolet)](https://github.com/ocornut/imgui)
[![License: MIT](https://img.shields.io/badge/License-MIT-green)](LICENSE)

---

</div>

## 🌟 프로젝트 소개

이 프로젝트는 학교 과학 수업용 **실시간 2D 핵분열 연쇄반응 시뮬레이터**입니다.  
선생님이 칠판 앞에서 직접 수십만 개의 중성자를 생성하고, 제어봉을 삽입하고, 임계 질량을 초과시키는 장면을 **60 FPS**로 보여줄 수 있습니다.

> 모든 물리 연산은 **GPU Compute Shader(OpenGL 4.3)** 에서 100% 처리됩니다.  
> CPU는 오직 UI 이벤트 처리와 셰이더 디스패치 명령만 담당합니다.

---

## ✨ 주요 기능

| 기능 | 설명 |
|------|------|
| 🚀 **GPU 물리 엔진** | 최대 500,000개 중성자를 OpenGL Compute Shader로 병렬 처리 |
| ⚡ **핵분열 연쇄반응** | U-235 / Pu-239 / U-238 핵분열 단면적 및 확률 기반 시뮬레이션 |
| ☢ **제온-135 독소** | I-135 → Xe-135 붕괴 체인 및 중성자 흡수 억제 효과 |
| 🌡 **온도 열역학** | 핵분열 열 생성 → 4방향 열전도 확산 → 물질별 냉각 속도 |
| 🔴 **방사선량 히트맵** | 누적 방사선량(Sv/h) 실시간 시각화 |
| 🎨 **3가지 렌더 모드** | 물질 뷰 / 방사선 뷰 / 온도 열화상 뷰 |
| 🖱 **GPU 기반 마우스 피킹** | CPU 순회 없이 GPU가 직접 가장 가까운 중성자를 계산 |
| ⏩ **시간 가속 (0.1x~100x)** | Sub-stepping으로 터널링 없는 고속 시뮬레이션 |
| 🏗 **실시간 환경 편집** | 연료/감속재/제어봉/벽을 마우스 브러시로 실시간 배치 |
| 💬 **ImGui 상세 툴팁** | 셀 정보(온도, 밀도, 독소)와 중성자 개별 정보 오버레이 |

---

## 🏗 기술 스택

```
언어         Kotlin 1.9 (JVM 17)
빌드         Gradle 8.6 (build.gradle.kts)
그래픽 API   LWJGL 3.3.3 + OpenGL 4.3 Compute Shader + SSBO
수학         JOML (Matrix4f, Vector2f — 카메라 & 투영 행렬)
UI           imgui-java 1.86 (ImGui GLFW + OpenGL3 backend)
난수         PCG Hash (GPU-side, 셰이더 내부 구현)
```

---

## 📐 시스템 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                        CPU (Kotlin)                         │
│  GLFW Events → ImGui UI → Sub-step Loop → glDispatchCompute │
└──────────────────────┬──────────────────────────────────────┘
                       │ Shader Dispatch Only
┌──────────────────────▼──────────────────────────────────────┐
│                        GPU (OpenGL 4.3)                     │
│                                                             │
│  ┌─────────────┐   ┌─────────────┐   ┌─────────────────┐   │
│  │ physics.comp│   │  decay.comp │   │ neutron.vert/frag│   │
│  │ - 이동/감속  │   │ - Xe붕괴    │   │ - Instanced Draw│   │
│  │ - 충돌/반사  │   │ - 열전도    │   │ - gl_InstanceID │   │
│  │ - 핵분열     │   │ - 방사선 확산│  │ - gl_PointCoord │   │
│  │ - 마우스 피킹│   └─────────────┘   └─────────────────┘   │
│  └─────────────┘                                            │
│                                                             │
│  SSBO 0: NeutronBuffer (500,000 × 32 bytes = 16 MB)        │
│  SSBO 1: GridBuffer    (512×512 × 48 bytes = 12.6 MB)      │
│  SSBO 2: GlobalCounters (12 bytes — atomic counters)        │
│  SSBO 3: SelectionBuffer (8 bytes — hover pick result)      │
└─────────────────────────────────────────────────────────────┘
```

---

## 💾 GPU 메모리 레이아웃

### Neutron (SSBO 0, std430, 32 bytes/struct)
```glsl
struct Neutron {
    vec2  position;   // 세계 좌표 (픽셀)
    vec2  velocity;   // 속도 벡터 (픽셀/초)
    float energy;     // 에너지 (< 15 = 열중성자, ≥ 15 = 고속중성자)
    int   isActive;   // 1=활성, 0=소멸
    // 8 bytes padding (std430 vec2 alignment)
};
```

### Cell (SSBO 1, std430, 48 bytes/struct)
```glsl
struct Cell {
    float u235_density;          // U-235 질량 (kg)
    float u238_density;          // U-238 질량 (kg)
    float pu239_density;         // Pu-239 질량 (kg)
    float u233_density;          // U-233 질량 (kg)
    float th232_density;         // Th-232 질량 (kg)
    float xe135_density;         // Xe-135 독소 농도
    int   structure_type;        // 0=진공, 1=경수, 2=중수, 3=흑연, 4=제어봉, 5=반사판, 6=벽
    float radiation_dose;        // 누적 방사선량 (Sv/h)
    float i135_density;          // I-135 전구체 농도
    float temperature;           // 온도 (°C)
    float thermal_conductivity;  // 열전도율
    float _pad;                  // std430 정렬 패딩
};
```

---

## ⚛ 핵물리 로직

### 핵분열 단면적 (확률)
| 원소 | 열중성자 (< 15 MeV) | 고속중성자 (≥ 15 MeV) |
|------|-------------------|---------------------|
| U-235  | **0.95** | 0.10 |
| Pu-239 | **0.90** | 0.25 |
| U-238  | 0.00    | 0.05 |
| Xe-135 | **0.99** (흡수) | — |

### 감속재 감쇠율 (1프레임당)
| 물질 | 속도 감쇠 | 효과 |
|------|---------|------|
| 경수 (Light Water) | **2.5%** | 빠른 열화, 좋은 냉각 |
| 중수 (Heavy Water) | **1.5%** | 중간 열화, 더 많은 핵분열 |
| 흑연 (Graphite)    | **1.0%** | 느린 열화, 열 축적 |

### 제온-135 독소 붕괴 수식
```
I-135 → Xe-135  변환율: 초당 5% (dt 기준)
Xe-135 → 안정   소멸율: 초당 1%
핵분열 발생 시:  I-135 농도 +1.0 (원자적 증가)
```

### 시간 가속 Sub-Stepping
```kotlin
val steps  = ceil(timeScale).toInt()
val stepDt = (1.0f / 60.0f) * (timeScale / steps)
repeat(steps) {
    glDispatchCompute(...)           // physics.comp
    glMemoryBarrier(SSBO_BIT)
    glDispatchCompute(...)           // decay.comp
    glMemoryBarrier(SSBO_BIT)
}
```

---

## 🎮 조작 방법

| 조작 | 기능 |
|------|------|
| **마우스 왼쪽 클릭 + 드래그** | 선택된 브러시 모드로 격자 편집 |
| **마우스 오버** | 셀/중성자 상세 정보 툴팁 표시 |
| **⏸ Pause / ▶ Resume** | 시뮬레이션 일시정지 |
| **🗑 Clear All** | GPU 버퍼 초기화 + 기본 장면 복원 |
| **Time Scale 슬라이더** | 0.1x ~ 100x 시간 배율 조절 |
| **Spawn 10k / 100k 버튼** | 화면 중앙에 대량 중성자 즉시 생성 |
| **Render Mode 라디오** | 물질 / 방사선 / 온도 뷰 전환 |

---

## 📦 설치 및 실행

### 시스템 요구사항

- **JDK 17** 이상
- **OpenGL 4.3** 지원 GPU (Intel Iris Xe 포함)
- Windows / Linux / macOS

### 실행 방법

```bash
# 저장소 클론
git clone https://github.com/jwyoon1220/IntegratedScienceIsBestSubject.git
cd IntegratedScienceIsBestSubject

# Gradle로 실행 (gradlew 자동 다운로드)
./gradlew run          # Linux / macOS
gradlew.bat run        # Windows
```

### Fat JAR 배포

```bash
./gradlew fatJar
# → build/libs/IntegratedScienceIsBestSubject-1.0.0-all.jar
java -jar build/libs/IntegratedScienceIsBestSubject-1.0.0-all.jar
```

> **macOS 주의:** LWJGL은 macOS에서 `-XstartOnFirstThread` JVM 인자가 필요합니다.  
> `build.gradle.kts`에 이미 설정되어 있습니다.

---

## 📁 프로젝트 구조

```
IntegratedScienceIsBestSubject/
├── build.gradle.kts                    # Gradle 빌드 설정 (LWJGL + JOML + ImGui)
├── settings.gradle.kts
├── src/
│   └── main/
│       ├── kotlin/com/atomengine/
│       │   ├── Main.kt                 # 진입점
│       │   ├── AtomEngine.kt           # 메인 엔진 (GLFW, ImGui, 렌더 루프)
│       │   ├── NeutronSSBO.kt          # 중성자 버퍼 래퍼
│       │   ├── GridSSBO.kt             # 격자 셀 버퍼 래퍼
│       │   ├── CounterSSBO.kt          # 글로벌 원자 카운터
│       │   ├── SelectionSSBO.kt        # GPU 마우스 피킹 버퍼
│       │   └── ShaderUtils.kt          # 셰이더 컴파일/링크 유틸
│       └── resources/shaders/
│           ├── physics.comp            # 핵분열 물리 컴퓨트 셰이더
│           ├── decay.comp              # Xe-135 붕괴 + 열역학 셰이더
│           ├── neutron.vert            # 중성자 인스턴스 버텍스 셰이더
│           ├── neutron.frag            # 중성자 원형 프래그먼트 셰이더
│           ├── grid.vert               # 배경 쿼드 버텍스 셰이더
│           └── grid.frag               # 배경 히트맵 프래그먼트 셰이더
└── README.md
```

---

## 🔧 성능 최적화 전략 (Intel Iris Xe)

1. **워크그룹 크기 256** — Iris Xe EU(Execution Unit) 점유율 최대화
2. **SSBO 직접 렌더링** — VBO 없이 `gl_InstanceID`로 NeutronBuffer 직접 읽기
3. **단 1회 Draw Call** — `glDrawArraysInstanced(GL_POINTS, 0, 1, activeCount)`
4. **조건부 `atomicAdd`** — 핵분열 판정 스레드에서만 원자적 연산 호출
5. **최소한의 CPU Readback** — 프레임당 카운터 12바이트 + 선택 8바이트만 읽기
6. **`glBufferSubData` 부분 업데이트** — 전체 버퍼 갱신 없이 브러시 영역만 업데이트
7. **`glClearBufferData`** — Clear All 시 CPU→GPU 데이터 전송 없이 GPU 내부에서 초기화

---

## 📖 물리 상수 레퍼런스

```glsl
// PCG Hash 난수 생성기 (GPU)
uint pcg_hash(uint seed) {
    uint state = seed * 747796405u + 2891336453u;
    uint word  = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}
float random_float(inout uint seed) {
    seed = pcg_hash(seed);
    return float(seed) / 4294967296.0;
}

// 각 스레드 고유 시드
uint seed = gl_GlobalInvocationID.x ^ frame_seed;
```

---

## 🤝 기여 방법

1. Fork this repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## 📜 라이선스

이 프로젝트는 [MIT License](LICENSE) 하에 배포됩니다.

---

<div align="center">

**통합과학은 정말... 정말로... 최고의 과목입니다 🧪⚛💥**

*Made with ☢ and lots of neutrons*

</div>
