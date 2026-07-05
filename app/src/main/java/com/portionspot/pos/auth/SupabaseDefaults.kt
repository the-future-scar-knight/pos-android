package com.portionspot.pos.auth

/**
 * The fixed PortionSpot Motors backend. The anon key is PUBLIC by design —
 * Row Level Security means it grants nothing on its own; every POS table
 * requires an authenticated, active `pos_staff` member (see the
 * `pos_lockdown_rls_policies` migration).
 */
object SupabaseDefaults {
    const val URL = "https://ucgvvxlhdooevngtraje.supabase.co"
    const val ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InVjZ3Z2eGxoZG9vZXZuZ3RyYWplIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzg5NDc3NDAsImV4cCI6MjA5NDUyMzc0MH0.7SW2dSXqyRN8jfK6Dho0XlOfvSkXblJ4pdJ3FEXv96U"
}
