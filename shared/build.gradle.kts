plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    implementation(libs.kotlinxSerialization)
    implementation(libs.kotlinxDatetime)
    implementation(libs.kotlinxCoroutines)
    implementation(libs.rabbitmq.amqp.client)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
}
