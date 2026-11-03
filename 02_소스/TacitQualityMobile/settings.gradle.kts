// ─────────────────────────────────────────────────────────────
//  제조암묵지기반 AI모델개발사업 (RS-2026-25590666)
//  품질검사 지원 APP — 모바일 모듈 (M-01 ~ M-06)
//  ㈜스네일 · 대외비 — 외부 공개 금지
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
    repositories { google(); mavenCentral() }   // 폐쇄망 반입 시 사내 미러로 교체
}
rootProject.name = "TacitQualityMobile"
include(":app")
