package com.skaterparkour.app

import kotlin.math.*

fun clampf(x: Float, a: Float, b: Float) = if (x < a) a else if (x > b) b else x
fun wrapA(a: Float): Float = atan2(sin(a), cos(a))
fun smooth(x: Float): Float { val t = clampf(x, 0f, 1f); return t * t * (3 - 2 * t) }
fun lerpf(a: Float, b: Float, t: Float) = a + (b - a) * t
fun rnd(a: Float, b: Float) = a + (Math.random().toFloat()) * (b - a)
