/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.smithy.kotlin.runtime.telemetry

import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic

/**
 * Global [TelemetryProvider]
 */
public object GlobalTelemetryProvider {
    private val _instance: AtomicRef<TelemetryProvider> = atomic(DefaultTelemetryProvider)

    /**
     * Get the globally configured [TelemetryProvider] used by the SDK
     */
    public val instance: TelemetryProvider
        get() = _instance.value

    /**
     * Set the global telemetry provider. This MUST be called only once and SHOULD be called early in the
     * application initialization process.
     */
    public fun set(provider: TelemetryProvider) {
        check(_instance.compareAndSet(DefaultTelemetryProvider, provider)) {
            "Global TelemetryProvider already set! Global provider must only be configured once!"
        }
    }
}

/**
 * Global telemetry provider used by default if one is not set explicitly. It is a no-op implementation for
 * everything but logging (which uses a sensible platform default if one exists, e.g. SLF4J for JVM).
 */
public val TelemetryProvider.Companion.Global: TelemetryProvider
    get() = GlobalTelemetryProvider.instance
