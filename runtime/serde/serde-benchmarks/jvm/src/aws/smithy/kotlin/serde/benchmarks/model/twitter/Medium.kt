/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

// Code generated by smithy-kotlin-codegen. DO NOT EDIT!

package aws.smithy.kotlin.serde.benchmarks.model.twitter

class Medium private constructor(builder: BuilderImpl) {
    val h: Int? = builder.h
    val resize: String? = builder.resize
    val w: Int? = builder.w

    companion object {
        @JvmStatic
        fun fluentBuilder(): FluentBuilder = BuilderImpl()

        fun builder(): DslBuilder = BuilderImpl()

        operator fun invoke(block: DslBuilder.() -> kotlin.Unit): Medium = BuilderImpl().apply(block).build()
    }

    override fun toString(): kotlin.String = buildString {
        append("Medium(")
        append("h=$h,")
        append("resize=$resize,")
        append("w=$w)")
    }

    override fun hashCode(): kotlin.Int {
        var result = h ?: 0
        result = 31 * result + (resize?.hashCode() ?: 0)
        result = 31 * result + (w ?: 0)
        return result
    }

    override fun equals(other: kotlin.Any?): kotlin.Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Medium

        if (h != other.h) return false
        if (resize != other.resize) return false
        if (w != other.w) return false

        return true
    }

    fun copy(block: DslBuilder.() -> kotlin.Unit = {}): Medium = BuilderImpl(this).apply(block).build()

    interface FluentBuilder {
        fun build(): Medium
        fun h(h: Int): FluentBuilder
        fun resize(resize: String): FluentBuilder
        fun w(w: Int): FluentBuilder
    }

    interface DslBuilder {
        var h: Int?
        var resize: String?
        var w: Int?

        fun build(): Medium
    }

    private class BuilderImpl() : FluentBuilder, DslBuilder {
        override var h: Int? = null
        override var resize: String? = null
        override var w: Int? = null

        constructor(x: Medium) : this() {
            this.h = x.h
            this.resize = x.resize
            this.w = x.w
        }

        override fun build(): Medium = Medium(this)
        override fun h(h: Int): FluentBuilder = apply { this.h = h }
        override fun resize(resize: String): FluentBuilder = apply { this.resize = resize }
        override fun w(w: Int): FluentBuilder = apply { this.w = w }
    }
}
