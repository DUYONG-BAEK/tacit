// ─────────────────────────────────────────────────────────────
//  제조암묵지기반 AI모델개발사업 (RS-2026-25590666)
//  품질검사 지원 — HMD 앱 (RealWear Navigator 520)
//  ㈜스네일 · 대외비 — 외부 공개 금지
//  역할: 촬영 · 영상 송출 · 화면 렌더 · 제스처 (설계서 ① HMD 앱)
// ─────────────────────────────────────────────────────────────
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "TacitQualityHmd"
include(":app")
