/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

// Code generated by smithy-kotlin-codegen. DO NOT EDIT!

package aws.smithy.kotlin.serde.benchmarks.model.twitter

class TwitterFeed private constructor(builder: BuilderImpl) {
    val searchMetadata: SearchMetadata? = builder.searchMetadata
    val statuses: List<Status>? = builder.statuses

    companion object {
        @JvmStatic
        fun fluentBuilder(): FluentBuilder = BuilderImpl()

        fun builder(): DslBuilder = BuilderImpl()

        operator fun invoke(block: DslBuilder.() -> kotlin.Unit): TwitterFeed = BuilderImpl().apply(block).build()
    }

    override fun toString(): kotlin.String = buildString {
        append("TwitterFeed(")
        append("searchMetadata=$searchMetadata,")
        append("statuses=$statuses)")
    }

    override fun hashCode(): kotlin.Int {
        var result = searchMetadata?.hashCode() ?: 0
        result = 31 * result + (statuses?.hashCode() ?: 0)
        return result
    }

    override fun equals(other: kotlin.Any?): kotlin.Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TwitterFeed

        if (searchMetadata != other.searchMetadata) return false
        if (statuses != other.statuses) return false

        return true
    }

    fun copy(block: DslBuilder.() -> kotlin.Unit = {}): TwitterFeed = BuilderImpl(this).apply(block).build()

    interface FluentBuilder {
        fun build(): TwitterFeed
        fun searchMetadata(searchMetadata: SearchMetadata): FluentBuilder
        fun statuses(statuses: List<Status>): FluentBuilder
    }

    interface DslBuilder {
        var searchMetadata: SearchMetadata?
        var statuses: List<Status>?

        fun build(): TwitterFeed
        /**
         * construct an [aws.benchmarks.json.model.SearchMetadata] inside the given [block]
         */
        fun searchMetadata(block: SearchMetadata.DslBuilder.() -> kotlin.Unit) {
            this.searchMetadata = SearchMetadata.invoke(block)
        }
    }

    private class BuilderImpl() : FluentBuilder, DslBuilder {
        override var searchMetadata: SearchMetadata? = null
        override var statuses: List<Status>? = null

        constructor(x: TwitterFeed) : this() {
            this.searchMetadata = x.searchMetadata
            this.statuses = x.statuses
        }

        override fun build(): TwitterFeed = TwitterFeed(this)
        override fun searchMetadata(searchMetadata: SearchMetadata): FluentBuilder = apply { this.searchMetadata = searchMetadata }
        override fun statuses(statuses: List<Status>): FluentBuilder = apply { this.statuses = statuses }
    }
}
