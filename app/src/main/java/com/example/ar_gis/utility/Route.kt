package com.example.ar_gis.utility

import com.esri.arcgisruntime.layers.Layer

data class Route (
    var layer: Layer,
    var roomsNumber: String
)