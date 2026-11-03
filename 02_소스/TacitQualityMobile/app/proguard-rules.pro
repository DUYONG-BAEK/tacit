# 릴리즈 빌드는 반드시 실기기에서 1회 이상 검증할 것.
-keepclassmembers class kr.co.snail.tacitq.mobile.model.** { *** Companion; }
-keepclasseswithmembers class kr.co.snail.tacitq.mobile.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
