/*
 * This source file is part of BetterModel.
 * Copyright (c) 2026 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.util

import kr.toxicity.model.api.physics.PhysicsParameters
import java.io.File

/**
 * A minimal line-based parser for model sidecar YAML files ({@code models/<model>.yaml}).
 *
 * Only the flat {@code physics} section is understood:
 * ```
 * physics:
 *   jiggle_胸:
 *     stiffness: 0.08
 *     damping: 0.12
 *     inertia: 1.0
 *     gravity: 0.3
 *     chain: true
 * ```
 * Keys other than stiffness/damping/inertia/gravity/chain and sections other than
 * {@code physics} are ignored. Missing values fall back to {@link PhysicsParameters#DEFAULT}.
 */
object ModelPhysicsParser {

    /**
     * Parses the physics section of a sidecar YAML file.
     *
     * @param file the sidecar file
     * @return bone name (raw name) to physics parameters
     */
    fun parse(file: File): Map<String, PhysicsParameters> {
        if (!file.isFile) return emptyMap()
        val result = HashMap<String, PhysicsParameters>()
        val default = PhysicsParameters.DEFAULT
        var sectionIndent = -1
        var bone: String? = null
        var boneIndent = -1
        var stiffness = Float.NaN
        var damping = Float.NaN
        var inertia = Float.NaN
        var gravity = Float.NaN
        var chain = false

        fun reset() {
            stiffness = Float.NaN
            damping = Float.NaN
            inertia = Float.NaN
            gravity = Float.NaN
            chain = false
        }

        file.readLines().forEach { raw ->
            val indent = raw.length - raw.trimStart().length
            val line = raw.trim().substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            if (sectionIndent < 0) {
                if (indent == 0 && line == "physics:") sectionIndent = indent
                return@forEach
            }
            if (indent <= sectionIndent) return@forEach
            if (line.endsWith(":")) {
                if (boneIndent < 0 || indent <= boneIndent) {
                    bone?.let { name ->
                        result[name] = PhysicsParameters(
                            if (stiffness.isNaN()) default.stiffness() else stiffness,
                            if (damping.isNaN()) default.damping() else damping,
                            if (inertia.isNaN()) default.inertia() else inertia,
                            if (gravity.isNaN()) default.gravity() else gravity,
                            chain
                        )
                    }
                    reset()
                    bone = line.dropLast(1).trim().unquote()
                    boneIndent = indent
                }
                return@forEach
            }
            bone ?: return@forEach
            val split = line.split(':', limit = 2)
            if (split.size != 2) return@forEach
            val value = split[1].trim().unquote()
            when (split[0].trim()) {
                "stiffness" -> value.toFloatOrNull()?.let { stiffness = it }
                "damping" -> value.toFloatOrNull()?.let { damping = it }
                "inertia" -> value.toFloatOrNull()?.let { inertia = it }
                "gravity" -> value.toFloatOrNull()?.let { gravity = it }
                "chain" -> chain = value.equals("true", ignoreCase = true)
                else -> Unit
            }
        }
        bone?.let { name ->
            result[name] = PhysicsParameters(
                if (stiffness.isNaN()) default.stiffness() else stiffness,
                if (damping.isNaN()) default.damping() else damping,
                if (inertia.isNaN()) default.inertia() else inertia,
                if (gravity.isNaN()) default.gravity() else gravity,
                chain
            )
        }
        return result
    }

    private fun String.unquote() = removeSurrounding("\"").removeSurrounding("'")
}
