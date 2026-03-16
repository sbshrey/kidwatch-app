package com.kidwatch.app.data.model

data class UserProfile(
    val name: String = "",
    val email: String = "",
    val role: String = "PARENT",
    val createdAt: Any? = null
)
