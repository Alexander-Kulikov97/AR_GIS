package com.example.ar_gis

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Environment.getExternalStorageDirectory
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.esri.arcgisruntime.concurrent.ListenableFuture
import com.esri.arcgisruntime.geometry.Point
import com.esri.arcgisruntime.loadable.LoadStatus
import com.esri.arcgisruntime.mapping.Basemap
import com.esri.arcgisruntime.mapping.MobileMapPackage
import com.esri.arcgisruntime.mapping.view.*
import com.esri.arcgisruntime.symbology.*
import com.esri.arcgisruntime.tasks.geocode.GeocodeResult
import com.esri.arcgisruntime.tasks.geocode.LocatorTask
import com.esri.arcgisruntime.tasks.geocode.ReverseGeocodeParameters
import com.esri.arcgisruntime.tasks.networkanalysis.*
import java.util.*
import java.util.concurrent.ExecutionException
import kotlin.collections.ArrayList


class MapActivity : AppCompatActivity() {

    private val TAG = MainActivity::class.java.simpleName
    private var mMarkerGraphicsOverlay: GraphicsOverlay? = null
    private var mRouteGraphicsOverlay: GraphicsOverlay? = null
    private var mRouteTask: RouteTask? = null
    private var mRouteParameters: RouteParameters? = null
    private var mMobileMapPackage: MobileMapPackage? = null
    private var mMapView: MapView? = null
    private var mMMPkTitle: String? = null
    private var mLocatorTask: LocatorTask? = null
    private var mCallout: Callout? = null
    private var mReverseGeocodeParameters: ReverseGeocodeParameters? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        mReverseGeocodeParameters = ReverseGeocodeParameters()
        mReverseGeocodeParameters?.setMaxResults(1)
        mReverseGeocodeParameters?.getResultAttributeNames()?.add("*")
        // retrieve the MapView from layout
        // retrieve the MapView from layout
        mMapView = findViewById<View>(R.id.mapView) as MapView
        // add route and marker overlays to map view
        // add route and marker overlays to map view
        mMarkerGraphicsOverlay = GraphicsOverlay()
        mRouteGraphicsOverlay = GraphicsOverlay()
        mMapView?.getGraphicsOverlays()?.add(mRouteGraphicsOverlay)
        mMapView?.getGraphicsOverlays()?.add(mMarkerGraphicsOverlay)
        // add the map from the mobile map package to the MapView
        // add the map from the mobile map package to the MapView
        loadMobileMapPackage("" + getExternalStorageDirectory() + getString(R.string.mapPath))
        mMapView?.setOnTouchListener(object : DefaultMapViewOnTouchListener(this, mMapView) {
            override fun onSingleTapConfirmed(motionEvent: MotionEvent): Boolean {
                // get the point that was clicked and convert it to a point in map coordinates
                val screenPoint = android.graphics.Point(Math.round(motionEvent.x), Math.round(motionEvent.y))
                // create a map point from screen point
                val mapPoint: com.esri.arcgisruntime.geometry.Point? = mMapView?.screenToLocation(
                    screenPoint
                )
                geoView(screenPoint, mapPoint)
                return true
            }
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (data != null) {
            // get the map number chosen in MapChooserActivity and load that map
            val mapNum = data.getIntExtra("map_num", -1)
            loadMap(mapNum)
            // dismiss any callout boxes
            mCallout?.dismiss()
            // clear any existing graphics
            mMarkerGraphicsOverlay!!.graphics.clear()
            mRouteGraphicsOverlay!!.graphics.clear()
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /**
     * Loads a mobile map package and map previews.
     *
     * @param path to location of mobile map package on device
     */
    private fun loadMobileMapPackage(path: String) {
        // create the mobile map package
        mMobileMapPackage = MobileMapPackage(path)
        // load the mobile map package asynchronously
        mMobileMapPackage!!.loadAsync()
        // add done listener which will load when package has maps
        mMobileMapPackage!!.addDoneLoadingListener(Runnable {
            // check load status and that the mobile map package has maps
            if (mMobileMapPackage!!.getLoadStatus() == LoadStatus.LOADED && !mMobileMapPackage!!.getMaps()
                    .isEmpty()
            ) {
                mLocatorTask = mMobileMapPackage!!.getLocatorTask()
                // default to display of first map in package
                loadMap(0)
                //loadMapPreviews()
            } else {
                val error =
                    "Mobile map package failed to load: " + mMobileMapPackage!!.getLoadError().message
                Log.e(TAG, error)
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            }
        })
    }

    /**
     * Loads map from the mobile map package for a given index.
     *
     * @param mapNum index of map in mobile map package
     */
    private fun loadMap(mapNum: Int) {
        val map = mMobileMapPackage!!.maps[mapNum]
        // check if the map contains transport networks
        if (map.transportationNetworks.isEmpty()) {
            // only allow routing on map with transport networks
            mRouteTask = null
        } else {
            mRouteTask = RouteTask(this, map.transportationNetworks[0])
            try {
                mRouteParameters = mRouteTask!!.createDefaultParametersAsync().get()
            } catch (e: ExecutionException) {
                val error = "Error creating route task default parameters: " + e.message
                Log.e(TAG, error)
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            } catch (e: InterruptedException) {
                val error = "Error creating route task default parameters: " + e.message
                Log.e(TAG, error)
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            }
        }
        mMapView!!.map = map
        mMapView!!.map.basemap = Basemap.createImagery()
    }



    /**
     * Defines a graphic symbol which represents geocoded locations.
     *
     * @return the stop graphic
     */
    private fun simpleSymbolForStopGraphic(): SimpleMarkerSymbol {
        val simpleMarkerSymbol = SimpleMarkerSymbol(
            SimpleMarkerSymbol.Style.CIRCLE, Color.RED, 12f
        )
        simpleMarkerSymbol.leaderOffsetY = 5f
        return simpleMarkerSymbol
    }

    /**
     * Defines a composite symbol consisting of the SimpleMarkerSymbol and a text symbol
     * representing the index of a stop in a route.
     *
     * @param simpleMarkerSymbol a SimpleMarkerSymbol which represents the background of the
     * composite symbol
     * @param index              number which corresponds to the stop number in a route
     * @return the composite symbol
     */
    private fun compositeSymbolForStopGraphic(
        simpleMarkerSymbol: SimpleMarkerSymbol,
        index: Int
    ): CompositeSymbol {
        val textSymbol = TextSymbol(
            12f, index.toString(), Color.BLACK,
            TextSymbol.HorizontalAlignment.CENTER, TextSymbol.VerticalAlignment.MIDDLE
        )
        val compositeSymbolList: MutableList<Symbol> = ArrayList()
        compositeSymbolList.addAll(Arrays.asList(simpleMarkerSymbol, textSymbol))
        return CompositeSymbol(compositeSymbolList)
    }

    /**
     * For a given point, returns a graphic.
     *
     * @param point           map point
     * @param isIndexRequired true if used in a route
     * @param index           stop number in a route
     * @return a Graphic at point with either a simple or composite symbol
     */
    private fun graphicForPoint(point: Point?, isIndexRequired: Boolean, index: Int?): Graphic {
        // make symbol composite if an index is required
        val symbol: Symbol
        symbol = if (isIndexRequired && index != null) {
            compositeSymbolForStopGraphic(simpleSymbolForStopGraphic(), index)
        } else {
            simpleSymbolForStopGraphic()
        }
        return Graphic(point, symbol)
    }

    /**
     * Shows the callout for a given graphic.
     *
     * @param graphic     the graphic selected by the user
     * @param tapLocation the location selected at a Point
     */
//    private fun showCalloutForGraphic(graphic: Graphic, tapLocation: Point) {
//        val calloutTextView = layoutInflater.inflate(R.layout.callout, null) as TextView
//        calloutTextView.text = graphic.attributes["Match_addr"].toString()
//        mCallout = mMapView!!.callout
//        mCallout.setLocation(tapLocation)
//        mCallout.setContent(calloutTextView)
//        mCallout.show()
//    }

    /**
     * Adds a graphic at a given point to GraphicsOverlay in the MapView. If RouteTask is not null
     * get index for stop symbol. If identifyGraphicsOverlayAsync returns no graphics, call
     * reverseGeocode and route, otherwise just call reverseGeocode.
     *
     * @param screenPoint point on the screen which the user selected
     * @param mapPoint    point on the map which the user selected
     */
    private fun geoView(screenPoint: android.graphics.Point, mapPoint: Point?) {
        if (mRouteTask != null || mLocatorTask != null) {
            if (mRouteTask == null) {
                mMarkerGraphicsOverlay!!.graphics.clear()
            }
            val identifyGraphicsResult: ListenableFuture<IdentifyGraphicsOverlayResult> =
                mMapView!!.identifyGraphicsOverlayAsync(
                    mMarkerGraphicsOverlay,
                    screenPoint,
                    12.0,
                    false
                )
            identifyGraphicsResult.addDoneListener {
                try {
                    val graphic: Graphic
                    if (identifyGraphicsResult.isDone() && identifyGraphicsResult.get()
                            .getGraphics().isEmpty()
                    ) {
                        graphic = if (mRouteTask != null) {
                            val index = mMarkerGraphicsOverlay!!.graphics.size + 1
                            graphicForPoint(mapPoint, true, index)
                        } else {
                            graphicForPoint(mapPoint, false, null)
                        }
                        mMarkerGraphicsOverlay!!.graphics.add(graphic)
                        reverseGeocode(mapPoint, graphic)
                        route()
                    } else if (identifyGraphicsResult.isDone()) {
                        // if graphic exists within screenPoint tolerance, show callout information of clicked graphic
                        reverseGeocode(mapPoint, identifyGraphicsResult.get().getGraphics().get(0))
                    }
                } catch (e: Exception) {
                    val error = "Error getting identify graphics result: " + e.message
                    Log.e(TAG, error)
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Calls reverseGeocode on a Locator Task and, if there is a result, passes the result to a
     * method which shows callouts.
     *
     * @param point   user generated map point
     * @param graphic used for marking the point on which the user touched
     */
    private fun reverseGeocode(point: com.esri.arcgisruntime.geometry.Point?, graphic: Graphic) {
        if (mLocatorTask != null) {
            val results: ListenableFuture<List<GeocodeResult>> =
                mLocatorTask!!.reverseGeocodeAsync(point, mReverseGeocodeParameters)
            results.addDoneListener {
                try {
                    val geocodeResult: List<GeocodeResult> = results.get()
                    if (geocodeResult.isEmpty()) {
                        // no result was found
                        mMapView!!.callout.dismiss()
                    } else {
                        graphic.attributes["Match_addr"] = geocodeResult[0].label
                        //showCalloutForGraphic(graphic, point)
                    }
                } catch (e: InterruptedException) {
                    val error = "Error getting geocode result: " + e.message
                    Log.e(TAG, error)
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                } catch (e: ExecutionException) {
                    val error = "Error getting geocode result: " + e.message
                    Log.e(TAG, error)
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Uses the last two markers drawn to calculate a route between them.
     */
    private fun route() {
        if (mMarkerGraphicsOverlay!!.graphics.size > 1 && mRouteParameters != null) {
            // create stops for last and second to last graphic
            val size = mMarkerGraphicsOverlay!!.graphics.size
            val graphics: MutableList<Graphic> = ArrayList()
            val lastGraphic = mMarkerGraphicsOverlay!!.graphics[size - 1]
            graphics.add(lastGraphic)
            val secondLastGraphic = mMarkerGraphicsOverlay!!.graphics[size - 2]
            graphics.add(secondLastGraphic)
            // add stops to the parameters
            mRouteParameters!!.setStops(stopsForGraphics(graphics))
            val routeResult: ListenableFuture<RouteResult> =
                mRouteTask!!.solveRouteAsync(mRouteParameters)
            routeResult.addDoneListener {
                try {
                    val route: Route = routeResult.get().getRoutes().get(0)
                    val routeGraphic = Graphic(
                        route.routeGeometry,
                        SimpleLineSymbol(
                            SimpleLineSymbol.Style.SOLID, Color.BLUE, 5.0f
                        )
                    )
                    mRouteGraphicsOverlay!!.graphics.add(routeGraphic)
                } catch (e: InterruptedException) {
                    val error = "Error getting route result: " + e.message
                    Log.e(TAG, error)
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    // if routing is interrupted, remove last graphic
                    mMarkerGraphicsOverlay!!.graphics.removeAt(mMarkerGraphicsOverlay!!.graphics.size - 1)
                } catch (e: ExecutionException) {
                    val error = "Error getting route result: " + e.message
                    Log.e(TAG, error)
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    mMarkerGraphicsOverlay!!.graphics.removeAt(mMarkerGraphicsOverlay!!.graphics.size - 1)
                }
            }
        }
    }

    /**
     * Converts a given list of graphics into a list of stops.
     *
     * @param graphics to be converted to stops
     * @return a list of stops
     */
    private fun stopsForGraphics(graphics: List<Graphic>): List<Stop>? {
        val stops: MutableList<Stop> = ArrayList()
        for (graphic in graphics) {
            val stop = Stop(graphic.geometry as com.esri.arcgisruntime.geometry.Point)
            stops.add(stop)
        }
        return stops
    }

    override fun onPause() {
        mMapView!!.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        mMapView!!.resume()
    }

    override fun onDestroy() {
        mMapView!!.dispose()
        super.onDestroy()
    }
}