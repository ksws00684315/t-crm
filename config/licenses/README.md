# 第三方许可原文与生成规则

`license-maven-plugin:2.7.1` 生成运行时清单（compile/runtime，包含传递依赖，排除 test/provided/system）。`scripts/generate-notices.py` 将清单与 Maven Central 同版本 JAR 中的 LICENSE、NOTICE、COPYING、COPYRIGHT 原文组合成各工程根目录的 `THIRD-PARTY-NOTICES`。

JAR 未附原文时，使用 Maven Central 的同版本 sources JAR，保留其中的原文与全部不同的源码版权/许可头；同版本 sources JAR 也缺原文时，使用 `originals.json` 登记的同版本官方源仓库原文。未找到任何原文即生成失败，不把缺失元数据视为合规。所有复制的原文保持完整；OR 许可的选择、AND 许可的组合见根目录 `osv-scanner.toml`，对应 Maven 生成器的登记见 `overrides.properties`。

`originals.json` 每条给出原文地址和固定版本。`originals/Apache-2.0.txt` 来自 Netty `netty-4.2.17.Final/LICENSE.txt`；`minlog-1.3.1.txt` 来自 EsotericSoftware/minlog `minlog-1.3.1/license.txt`（BSD-3-Clause）；`mybatis-plus-3.5.17.txt` 来自 baomidou/mybatis-plus `v3.5.17/LICENSE`（Apache-2.0）；`netty-4.2.17.Final.txt` 保留对应官方 LICENSE、NOTICE 与当前 Java 运行时涉及的组件原文。Netty NOTICE 同时描述可选组件；当前清单没有 macOS 原生 DNS 模块，其 dnsinfo/APSL 源码不在运行时 JAR 中。原文获取日期：2026-10-05。

生成命令（在 backend 或 redirect 中）：

```bash
mvn generate-resources dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/runtime-classpath.txt
python3 ../scripts/generate-notices.py .
git diff -- THIRD-PARTY-NOTICES
```

源 JAR 下载缓存在 `target/license-sources`，不会进入仓库。生成结果按坐标与路径排序，不含构建时间或本机绝对路径。CI 使用同一命令再生成并执行 `git diff --exit-code`。依赖升级后必须重新核对原文、更新准确版本的登记并提交新的声明；禁止通过排除运行时依赖解决告警。Jar 中包含 `META-INF/THIRD-PARTY-NOTICES`，任务 03 的 Dockerfile 还须复制根目录声明。
