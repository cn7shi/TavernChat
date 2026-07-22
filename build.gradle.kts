plugins {
    kotlin("jvm") version "1.9.22"
}

repositories {
    mavenCentral()
}

dependencies {
    // 协程核心库（包含在最终 App 打包产物中）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // 协程测试拓展库（仅在本地单元测试时有效，不会增加 App 体积，提供 runTest 虚拟时间加速）
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation(kotlin("test"))
}
