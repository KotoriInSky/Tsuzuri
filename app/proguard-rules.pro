# R8 规则。注意 ProGuard 语法只认 `#` 注释，不认 `//`。
#
# 为什么这个模块**必须**开混淆：模块 APK 的 dex 会被 mmap 进每一个 scope 进程
# （包括 system_server）。不开混淆时全量 Compose + MiuiX 是 32MB dex，
# 那是白白压在每个系统进程的地址空间里。

# --- LSPosed 入口类 ---------------------------------------------------
# java_init.list 里写的是**字符串**形式的全限定类名，R8 改类名它就失效了。
# 两种解法：① 用 -adaptresourcefilecontents 让资源内容跟着改名；
#          ② 干脆不让 R8 改这一个类名。
# 选 ② —— 它不依赖「AGP 会不会把 META-INF/xposed 当作待适配资源」这个未经验证的假设，
# 代价只是少优化一个类，而这是全项目最不该出问题的一个类。
-keep class io.github.kotoriinsky.tsuzuri.hook.HookEntry { *; }

# --- 契约层 -----------------------------------------------------------
# Catalog 是前后端共享的单一事实来源，按 key 查表使用，整套保住。
-keep class io.github.kotoriinsky.tsuzuri.core.** { *; }

# --- 后端特性实现 -----------------------------------------------------
# Feature 是 object 单例，由 HookRegistry 持有。显式保住，
# 防止 R8 认为 install() 没有外部调用者而把实现整块删掉。
-keep class io.github.kotoriinsky.tsuzuri.hook.feature.** { *; }

# --- 设置界面里由 AndroidManifest 声明的组件 ---------------------------------
# 这两个 ContentProvider 是**跨进程**的通信口：调用者（SystemUI / 负一屏）跑在
# 别的进程里，R8 在依赖图里**看不见任何调用者**。AGP 一般会自动为 manifest 里声明的
# 组件生成 keep 规则，但这条通道的失败方式是**静默的**——
# debug 一切正常、release 下 SystemUI 读不到图标，而且不会报错（只会「图标没变」）。
# 这个项目已经在「静默失效」上吃过几次亏，所以这里显式钉住，不依赖框架的默认行为。
#
# `-dontobfuscate` 已经在下面，所以只需要防**删除**，不需要防改名。
-keep class io.github.kotoriinsky.tsuzuri.ui.state.IconFileProvider { *; }
-keep class io.github.kotoriinsky.tsuzuri.ui.state.FanShortcutCatalogProvider { *; }
-keep class io.github.kotoriinsky.tsuzuri.ui.state.PortalTempImageProvider { *; }

# --- Compose ----------------------------------------------------------
# Compose 运行时大量依赖函数引用和 lambda 合成类，规则由 androidx 的
# consumer rules 自带，这里只需要保住注解，否则 @Composable 的
# 可空性推断会出错。
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# --- 关键：hook lambda 不能被 R8 改名或删除 ---------------------------
# 实测踩到过：release 装机后 LSPosed 日志里刷了几千条
#   AbstractMethodError: abstract method ... XposedInterface$Hooker.intercept(...)
#   at ...writeToParcel(...)
# 原因：io.github.libxposed:api 是 compileOnly，R8 在依赖图里**看不见** Hooker 接口，
# 于是把 `module.hook(m).intercept { }` 生成的 lambda 里的 intercept 当成普通方法，
# 改名/删掉之后运行时就对不上接口了。异常被 PROTECTIVE 模式兜住，所以不崩，
# 只是**整个 hook 静默失效** —— 这是最难发现的一类故障。
#
# 官方 README 的规则只覆盖入口类，不解决这一条。
#
# 第二次踩到（0.1.0 release）：下面这条 keep 对 `{ chain -> }` 写法**不生效**。
# Kotlin 把 SAM lambda 编成 invokedynamic，R8 脱糖时才生成 `$$ExternalSyntheticLambda`，
# keep 规则匹配不到这种 R8 自己造的类；它又看不见 Hooker，于是删掉 intercept、
# 再把全项目约 88 个 hook lambda 横向合并成一个空类。
# 真正的修法在 app/build.gradle.kts：给 :app 加 compileOnly(libxposed api)，
# 让 R8 把 Hooker 当已知的库接口，自动保住所有实现。这条 keep 只管 `object : Hooker` 写法。
-dontobfuscate
-keepclassmembers class ** {
    public ** intercept(...);
}

# --- 框架 API：故意**不** -dontwarn -----------------------------------
# io.github.libxposed:api 永远 compileOnly（打进 APK 会被 LSPosed 拒绝加载），
# R8 通过 :app 的 compileOnly 把它当库类看。以前这里有 -dontwarn，
# 正是它把「R8 看不见 Hooker」压成了静默故障。现在要是有人删了那条 compileOnly，
# R8 会直接报 Missing class 让构建失败 —— 这就是构建期的守卫。

# --- 去掉 release 里的调试日志 ----------------------------------------
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}

# libtsgif.so 的 JNI 符号按全限定类名绑定（Java_io_github_..._PortalGifNative_*），类名与 native 方法名都不能改。
-keep class io.github.kotoriinsky.tsuzuri.hook.feature.portal.PortalGifNative {
    native <methods>;
}
-keep class io.github.kotoriinsky.tsuzuri.hook.feature.portal.PortalWebpNative {
    native <methods>;
}
