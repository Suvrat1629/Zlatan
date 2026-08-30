package com.sih26168.idr.core.types

/**
 * User-selected vehicle context. CAR and BIKE currently share the vehicle-trained model
 * weights unchanged; WALK damps the speed output (scale + hard cap from config) because the
 * car-trained models fabricate vehicle speeds from gait motion (measured in the field:
 * 6 km/h of walking read as 20-35 km/h).
 */
enum class VehicleMode { WALK, BIKE, CAR }
