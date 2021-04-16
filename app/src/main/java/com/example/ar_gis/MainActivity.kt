package com.example.ar_gis

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Service
import android.content.DialogInterface
import android.content.DialogInterface.OnMultiChoiceClickListener
import android.content.Intent
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Environment.getExternalStorageDirectory
import android.preference.PreferenceManager
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.webkit.URLUtil
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.esri.arcgisruntime.concurrent.ListenableFuture
import com.esri.arcgisruntime.geometry.*
import com.esri.arcgisruntime.layers.Layer
import com.esri.arcgisruntime.loadable.LoadStatus
import com.esri.arcgisruntime.loadable.LoadStatusChangedListener
import com.esri.arcgisruntime.location.LocationDataSource
import com.esri.arcgisruntime.mapping.*
import com.esri.arcgisruntime.mapping.view.*
import com.esri.arcgisruntime.portal.Portal
import com.esri.arcgisruntime.portal.PortalItem
import com.esri.arcgisruntime.portal.PortalUser
import com.esri.arcgisruntime.security.AuthenticationManager
import com.esri.arcgisruntime.security.DefaultAuthenticationChallengeHandler
import com.esri.arcgisruntime.symbology.*
import com.esri.arcgisruntime.tasks.geocode.LocatorTask
import com.esri.arcgisruntime.tasks.geocode.ReverseGeocodeParameters
import com.esri.arcgisruntime.tasks.networkanalysis.*
import com.esri.arcgisruntime.toolkit.ar.ArLocationDataSource
import com.esri.arcgisruntime.toolkit.ar.ArcGISArView
import com.example.ar_gis.utility.PortalAGOL
import com.github.clans.fab.FloatingActionButton
import com.google.ar.core.Plane
import java.util.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit


class MainActivity : AppCompatActivity() {

    private val TAG = MainActivity::class.java.simpleName

    private var mHasConfiguredScene = false

    private var mArView: ArcGISArView? = null

    private var mPortal: Portal? = null

    private var mPortalAGOL: PortalAGOL = PortalAGOL();

    /** Высота для Уфы (может менятся в зависимости от местности) mCurrentVerticalOffset = 150 */
    private val mCurrentVerticalOffset = 1.0
    private var mAzimuth = 0.0 // degree
    private var mSensorManager: SensorManager? = null
    private var mAccelerometer: Sensor? = null
    private var mMagnetometer: Sensor? = null
    private var haveAccelerometer = false
    private var haveMagnetometer = false

    private var ActionGpsLoc: FloatingActionButton? = null
    private var ActionTapSensorNavigation:FloatingActionButton? = null
    private var ActionTapArTerritory:FloatingActionButton? = null
    private var ActionLayers:FloatingActionButton? = null
    private var ActionOpen:FloatingActionButton? = null

    private var mMarkerGraphicsOverlay: GraphicsOverlay? = null
    private var mRouteGraphicsOverlay: GraphicsOverlay? = null
    private var mRouteTask: RouteTask? = null
    private var mRouteParameters: RouteParameters? = null
    //private val mMapPreviews: ArrayList<MapPreview> = ArrayList()
    private var mMobileScenePackage: MobileScenePackage? = null
    private var mMMPkTitle: String? = null
    private var mLocatorTask: LocatorTask? = null
    private val mCallout: Callout? = null
    private var mReverseGeocodeParameters: ReverseGeocodeParameters? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mArView = findViewById(R.id.arView)
        mArView?.registerLifecycle(lifecycle)

        mReverseGeocodeParameters = ReverseGeocodeParameters()
        mReverseGeocodeParameters!!.setMaxResults(1)
        mReverseGeocodeParameters!!.getResultAttributeNames().add("*")

        // add route and marker overlays to map view
        mMarkerGraphicsOverlay = GraphicsOverlay()
        mRouteGraphicsOverlay = GraphicsOverlay()
        mArView?.sceneView?.getGraphicsOverlays()?.add(mRouteGraphicsOverlay)
        mArView?.sceneView?.getGraphicsOverlays()?.add(mMarkerGraphicsOverlay)

        // add the map from the mobile map package to the MapView
        loadMobileScenePackage("" + getExternalStorageDirectory() + getString(R.string.scenePath))

        val point1: Point = Point(6229296.901368923, 7312296.581373852);
        val point2: Point = Point(6229280.5453083, 7312244.386298041);

        geoView(point1)
        geoView(point2)

        ActionOpen = findViewById(R.id.floatingActionOpen) as FloatingActionButton
        //ActionTapSensorNavigation= findViewById(R.id.floatingActionTapSensorNavigation) as FloatingActionButton
        ActionLayers = findViewById(R.id.floatingActionLayers) as FloatingActionButton
        //ActionTapArTerritory = findViewById(R.id.floatingActionTapArTerritory) as FloatingActionButton
        ActionGpsLoc = findViewById(R.id.floatingActionGpsLoc) as FloatingActionButton

        ActionOpen!!.setOnClickListener { AuthAgol() }
        //ActionTapSensorNavigation!!.setOnClickListener { setupArView() }
        ActionLayers!!.setOnClickListener { showLayers() }
        //ActionTapArTerritory!!.setOnClickListener { setupTerritoryArViewClick() }
        ActionGpsLoc!!.setOnClickListener {
            val intent = Intent(
                this@MainActivity,
                MapActivity::class.java
            )
            val bundle = Bundle()
            startActivity(intent, bundle)
        }

    }

    private fun loadMobileScenePackage(path: String) {
        // create the mobile map package
        mMobileScenePackage = MobileScenePackage(path)
        // load the mobile map package asynchronously
        mMobileScenePackage?.loadAsync()
        // add done listener which will load when package has maps
        mMobileScenePackage?.addDoneLoadingListener {
            // check load status and that the mobile map package has maps
            if (mMobileScenePackage?.getLoadStatus() === LoadStatus.LOADED && !mMobileScenePackage?.scenes?.isEmpty()!!) {
                mLocatorTask = mMobileScenePackage?.locatorTask
                // default to display of first map in package
                loadScene(0)
            } else {
                val error = "Mobile map package failed to load: " + mMobileScenePackage?.getLoadError()?.message
                Log.e(TAG, error)
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadScene(mapNum: Int) {
        val scene: ArcGISScene? = mMobileScenePackage?.scenes?.get(mapNum)
        // check if the map contains transport networks
        if (scene?.transportationNetworks?.isEmpty()!!) {
            // only allow routing on map with transport networks
            mRouteTask = null
        } else {
            mRouteTask = RouteTask(this, scene?.transportationNetworks[0])
            try {
                mRouteParameters = mRouteTask?.createDefaultParametersAsync()?.get()
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
        mArView?.sceneView?.scene = scene

        mArView?.translationFactor = 1.0
        mArView?.locationDataSource = locationDataSource
        mArView?.clippingDistance = 400.0
    }

    private fun geoView(mapPoint: Point) {
        if (mRouteTask != null || mLocatorTask != null) {
            if (mRouteTask == null) {
                mMarkerGraphicsOverlay!!.graphics.clear()
            }
            val graphic: Graphic
            if (mRouteTask != null) {
                val index = mMarkerGraphicsOverlay!!.graphics.size + 1
                graphic = graphicForPoint(mapPoint, true, index)!!
            } else {
                graphic = graphicForPoint(mapPoint, false, null)!!
            }
            mMarkerGraphicsOverlay!!.graphics.add(graphic)
            //reverseGeocode(mapPoint, graphic)
            route()
        }
    }

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
                        route.getRouteGeometry(),
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
                    mMarkerGraphicsOverlay!!.graphics
                        .removeAt(mMarkerGraphicsOverlay!!.graphics.size - 1)
                } catch (e: ExecutionException) {
                    val error = "Error getting route result: " + e.message
                    Log.e(TAG, error)
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    mMarkerGraphicsOverlay!!.graphics
                        .removeAt(mMarkerGraphicsOverlay!!.graphics.size - 1)
                }
            }
        }
    }

    private fun stopsForGraphics(graphics: List<Graphic>): List<Stop?>? {
        val stops: MutableList<Stop> = ArrayList()
        for (graphic in graphics) {
            val stop = Stop(graphic.geometry as Point)
            stops.add(stop)
        }
        return stops
    }

//    private fun reverseGeocode(point: Point, graphic: Graphic) {
//        if (mLocatorTask != null) {
//            val results: ListenableFuture<List<GeocodeResult>> =
//                mLocatorTask!!.reverseGeocodeAsync(point, mReverseGeocodeParameters)
//            results.addDoneListener {
//                try {
//                    val geocodeResult: List<GeocodeResult> = results.get()
//                    if (geocodeResult.isEmpty()) {
//                        // no result was found
//                        mArView?.sceneView?.getCallout().dismiss()
//                    } else {
//                        graphic.attributes["Match_addr"] = geocodeResult[0].getLabel()
//                        showCalloutForGraphic(graphic, point)
//                    }
//                } catch (e: InterruptedException) {
//                    val error = "Error getting geocode result: " + e.message
//                    Log.e(TAG, error)
//                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
//                } catch (e: ExecutionException) {
//                    val error = "Error getting geocode result: " + e.message
//                    Log.e(TAG, error)
//                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
//                }
//            }
//        }
//    }

    private fun graphicForPoint(point: Point, isIndexRequired: Boolean, index: Int?): Graphic? {
        // make symbol composite if an index is required
        val symbol: Symbol?
        if (isIndexRequired && index != null) {
            symbol = compositeSymbolForStopGraphic(simpleSymbolForStopGraphic()!!, index)
        } else {
            symbol = simpleSymbolForStopGraphic()
        }
        return Graphic(point, symbol)
    }

    private fun simpleSymbolForStopGraphic(): SimpleMarkerSymbol? {
        val simpleMarkerSymbol = SimpleMarkerSymbol(
            SimpleMarkerSymbol.Style.CIRCLE, Color.RED, 12f
        )
        simpleMarkerSymbol.setLeaderOffsetY(5f)
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
    ): CompositeSymbol? {
        val textSymbol = TextSymbol(
            12f, index.toString(), Color.BLACK,
            TextSymbol.HorizontalAlignment.CENTER, TextSymbol.VerticalAlignment.MIDDLE
        )
        val compositeSymbolList: MutableList<Symbol> = ArrayList()
        compositeSymbolList.addAll(Arrays.asList(simpleMarkerSymbol, textSymbol))
        return CompositeSymbol(compositeSymbolList)
    }
    
    private fun AuthAgol() {
        mPortalAGOL.webScenes.clear()
        val PREF_PORTAL_URL = "pref_portal_url"
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val txtUrl = EditText(this)
        txtUrl.setLines(1)
        txtUrl.maxLines = 1
        txtUrl.inputType = InputType.TYPE_TEXT_VARIATION_URI
        txtUrl.setText(prefs.getString(PREF_PORTAL_URL, getString(R.string.default_portal_url)))
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_title_portal_url)
            .setView(txtUrl)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok,
                DialogInterface.OnClickListener { dialog, which ->
                    AuthenticationManager.setAuthenticationChallengeHandler(
                        DefaultAuthenticationChallengeHandler(this@MainActivity)
                    )
                    val sUrl = txtUrl.text.toString()
                    if (!URLUtil.isValidUrl(sUrl)) {
                        dialog.dismiss()
                        val toast: Toast = Toast.makeText(
                            this@MainActivity,
                            R.string.msg_invalid_portal_url,
                            Toast.LENGTH_LONG
                        )
                        toast.setGravity(Gravity.CENTER_VERTICAL, 0, 0)
                        toast.show()
                        return@OnClickListener
                    }
                    // Note that mPortal was loaded up at the end of onCreate()
                    mPortal = Portal(sUrl, true)
                    mPortal?.addDoneLoadingListener(Runnable {
                        if (mPortal?.getLoadStatus() != LoadStatus.LOADED) {
                            var msg: String?
                            if (Locale.getDefault().language == Locale.ENGLISH.language) {
                                msg = mPortal?.getLoadError()?.cause?.getLocalizedMessage()
                                if (mPortal?.getLoadError()?.getAdditionalMessage() != null)
                                    msg += "; " + mPortal?.getLoadError()?.getAdditionalMessage()
                            } else { // Non-English language; use string resource
                                msg = getString(R.string.err_portal_user_problem)
                            }
                            val ad: AlertDialog = AlertDialog.Builder(this@MainActivity)
                                .setMessage(msg)
                                .setPositiveButton(android.R.string.ok, null)
                                .create()
                            ad.show()
                            return@Runnable
                        }

                        // save the url for next time
                        prefs.edit().putString(PREF_PORTAL_URL, sUrl).apply()
                        val user: PortalUser = mPortal!!.getUser()
                        val contentFuture = user.fetchContentAsync()
                        contentFuture.addDoneListener {
                            try {
                                //iterate items in root folder...
                                val portalUserContent = contentFuture.get()
                                for (item in portalUserContent.items) {
                                    if (item.type == PortalItem.Type.WEB_SCENE)
                                        mPortalAGOL.webScenes.add(item)
                                }
                                //iterate user's folders
                                val folderItemResults: MutableList<Map.Entry<String, ListenableFuture<List<PortalItem>>>> =
                                    ArrayList()
                                for (folder in portalUserContent.folders) {
                                    //fetch the items in each folder
                                    val folderFuture =
                                        user.fetchContentInFolderAsync(folder.folderId)
                                    folderItemResults.add(
                                        AbstractMap.SimpleEntry(
                                            folder.title, folderFuture
                                        )
                                    )
                                }
                                for ((key, value) in folderItemResults) {
                                    try {
                                        // Use synchronous, blocking get() to wait on all portal item results
                                        val folderItems = value[2000, TimeUnit.MILLISECONDS]

                                        for (folderItem in folderItems)
                                            if (folderItem.type == PortalItem.Type.WEB_SCENE)
                                                mPortalAGOL.webScenes.add(folderItem)

                                    } catch (exc: java.lang.Exception) {
                                        Log.i(
                                            TAG,
                                            "Error getting items in folder '" + key + "': " + exc.localizedMessage
                                        )
                                    }
                                }
                                mPortalAGOL.selectSceneDialog(this@MainActivity)

                            } catch (e: java.lang.Exception) {
                                e.printStackTrace()
                            }
                        } // End Runnable
                    })
                    mPortal!!.loadAsync()
                })
            .show()
    }

    private fun setupTerritoryArViewClick(){
        Toast.makeText(this, "Старт территории в Ar ", Toast.LENGTH_LONG).show()
        mArView?.sceneView?.setOnTouchListener(object :
            DefaultSceneViewOnTouchListener(mArView?.sceneView) {
            @SuppressLint("ClickableViewAccessibility")
            override fun onSingleTapConfirmed(motionEvent: MotionEvent?): Boolean {
                motionEvent?.let {
                    with(android.graphics.Point(motionEvent.x.toInt(), motionEvent.y.toInt())) {
                        val sphere = SimpleMarkerSceneSymbol.createSphere(
                            Color.CYAN,
                            0.25,
                            SceneSymbol.AnchorPosition.BOTTOM
                        )
                        if (mArView!!.sceneView.scene == null) {
                            val scene = ArcGISScene(mPortalAGOL.sceneItem)
                            scene.loadAsync()
                            scene.addLoadStatusChangedListener(LoadStatusChangedListener { loadStatusChangedEvent ->
                                mArView!!.sceneView.scene = scene
                            })
                        } else {

                        }

                        addElevationSource(mArView?.sceneView?.scene)
                        getLocation()
                        mArView?.locationDataSource = locationDataSource
                        mArView?.translationFactor = 1.0
                        mArView?.clippingDistance = 1000.0

                        mArView?.arScreenToLocation(this)?.let {
                            val graphic = Graphic(it, sphere)
                            sphereOverlay.graphics.add(graphic)
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun setupTerritoryArView(){

        Toast.makeText(this, "Старт территории в Ar ", Toast.LENGTH_LONG).show()

        if(mArView!!.sceneView.scene == null) {
            val scene = ArcGISScene(mPortalAGOL.sceneItem)
            scene.loadAsync()
            scene.addLoadStatusChangedListener(LoadStatusChangedListener { loadStatusChangedEvent ->
                mArView!!.sceneView.scene = scene
            })
        }

        addElevationSource(mArView?.sceneView?.scene)
        mArView?.locationDataSource = ArLocationDataSource(this)
        getLocation()
        mArView?.translationFactor = 1.0

    }

    private fun getLocation(){

        mSensorManager = getSystemService(Service.SENSOR_SERVICE) as SensorManager?;

        this.mAccelerometer = this.mSensorManager!!.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        this.haveAccelerometer = this.mSensorManager!!.registerListener(
            mSensorEventListener,
            this.mAccelerometer,
            SensorManager.SENSOR_DELAY_GAME
        );

        this.mMagnetometer = this.mSensorManager!!.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        this.haveMagnetometer = this.mSensorManager!!.registerListener(
            mSensorEventListener,
            this.mMagnetometer,
            SensorManager.SENSOR_DELAY_GAME
        );

        mArView!!.locationDataSource!!.addLocationChangedListener { locationChangedEvent: LocationDataSource.LocationChangedEvent ->
            val updatedLocation = locationChangedEvent.location.position
            mArView!!.originCamera = Camera(
                Point(updatedLocation.x, updatedLocation.y, mCurrentVerticalOffset),
                mAzimuth,
                mArView!!.originCamera.pitch,
                mArView!!.originCamera.roll
            )

            Toast.makeText(
                this,
                "Азимут: " + mAzimuth.toString() + " Высота: " + mCurrentVerticalOffset.toString() + " Z величина сцены " + updatedLocation.z,
                Toast.LENGTH_LONG
            ).show()
            //altitudeText.setText(" " + mArView!!.originCamera.heading)
        }
    }

    private val mSensorEventListener: SensorEventListener = object : SensorEventListener {
        var gData = FloatArray(3) // accelerometer
        var mData = FloatArray(3) // magnetometer
        var rMat = FloatArray(9)
        var iMat = FloatArray(9)
        var orientation = FloatArray(3)
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        override fun onSensorChanged(event: SensorEvent) {
            var data: FloatArray
            when (event.sensor.getType()) {
                Sensor.TYPE_ACCELEROMETER -> gData = event.values.clone()
                Sensor.TYPE_MAGNETIC_FIELD -> mData = event.values.clone()
                else -> return
            }
            if (SensorManager.getRotationMatrix(rMat, iMat, gData, mData)) {
                mAzimuth = (Math.toDegrees(
                    SensorManager.getOrientation(rMat, orientation).get(0).toDouble()
                ) + 360).toInt() % 360.toDouble()
                Log.d("AzimuthTag", "Azimuth:$mAzimuth")
            }
        }
    }

    private val locationDataSource: LocationDataSource get() = ArLocationDataSource(this)

    private val sphereOverlay: GraphicsOverlay by lazy {
        GraphicsOverlay().apply {
            this.sceneProperties.surfacePlacement =
                LayerSceneProperties.SurfacePlacement.ABSOLUTE
            mArView?.sceneView?.graphicsOverlays?.add(this)
        }
    }

    private fun addElevationSource(scene: ArcGISScene?) {
        if(scene == null){
            return
        }
        val elevationSource =
            ArcGISTiledElevationSource("https://elevation3d.arcgis.com/arcgis/rest/services/WorldElevation3D/Terrain3D/ImageServer")
        val surface = Surface()
        surface.elevationSources.add(elevationSource)
        surface.name = "baseSurface"
        surface.isEnabled = true
        surface.backgroundGrid.color = Color.TRANSPARENT
        surface.backgroundGrid.gridLineColor = Color.TRANSPARENT
        surface.navigationConstraint = NavigationConstraint.NONE
        scene.baseSurface = surface
    }

    private fun setupArView() {

        // show simple instructions to the user. Refer to the README for more details
        Toast.makeText(this, R.string.camera_instruction_message, Toast.LENGTH_LONG).show()
        mArView?.sceneView?.setOnTouchListener(object :
            DefaultSceneViewOnTouchListener(mArView?.sceneView) {
            override fun onSingleTapConfirmed(motionEvent: MotionEvent): Boolean {
                // результат нажатия на экран
                val hitResults = mArView?.arSceneView!!.arFrame!!.hitTest(motionEvent)
                // проверка, распознается ли точка касания как плоскость ARCore
                if (!hitResults.isEmpty() && hitResults[0].trackable is Plane) {

                    val plane: Plane = hitResults[0].trackable as Plane
                    Toast.makeText(
                        this@MainActivity,
                        "Обнаруженная плоскость с шириной: " + plane.getExtentX(),
                        Toast.LENGTH_SHORT
                    ).show()

                    val screenPoint = android.graphics.Point(
                        Math.round(motionEvent.x),
                        Math.round(motionEvent.y)
                    )

                    if (mArView?.setInitialTransformationMatrix(screenPoint)!!) {
                        // the scene hasn't been configured
                        //if (!mHasConfiguredScene) {
                        loadSceneFromAGOL(mPortalAGOL.sceneItem, plane)

//                        } else if (mArView?.sceneView?.scene != null) {
//                            // use information from the scene to determine the origin camera and translation factor
//                            updateTranslationFactorAndOriginCamera(
//                                mArView?.sceneView?.scene!!,
//                                plane
//                            )
//                        }
                    }
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.not_plane_error),
                        Toast.LENGTH_SHORT
                    ).show()
                    Log.e(TAG, getString(R.string.not_plane_error))
                }
                return super.onSingleTapConfirmed(motionEvent)
            }

            // disable pinch zooming
            override fun onScale(scaleGestureDetector: ScaleGestureDetector): Boolean {
                return true
            }
        })
    }

//    private fun requestPermissions() {
//        // define permission to request
//        val reqPermission =
//            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.CAMERA)
//        val requestCode = 2
//        if (ContextCompat.checkSelfPermission(
//                this,
//                reqPermission[0]
//            ) == PackageManager.PERMISSION_GRANTED
//        ) {
//            setupArView()
//        } else {
//            // request permission
//            ActivityCompat.requestPermissions(this, reqPermission, requestCode)
//        }
//    }

//    override fun onRequestPermissionsResult(
//        requestCode: Int, permissions: Array<String?>,
//        grantResults: IntArray
//    ) {
//        if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//            setupArView()
//        } else {
//            // report to user that permission was denied
//            Toast.makeText(
//                this,
//                getString(R.string.tabletop_map_permission_denied),
//                Toast.LENGTH_SHORT
//            ).show()
//        }
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
//    }

    /**
     * Load the mobile scene package and get the first (and only) scene inside it. Set it to the ArView's SceneView and
     * set the base surface to opaque and remove any navigation constraint, thus allowing the user to look at a scene
     * from below. Then call updateTranslationFactorAndOriginCamera with the plane detected by ArCore.
     *
     * @param plane detected by ArCore based on a tap from the user. The loaded scene will be pinned on this plane.
     */
    private fun loadSceneFromAGOL(currentScene: PortalItem?, plane: Plane){

        val scene = ArcGISScene(currentScene)
        scene.loadAsync()
        scene.addLoadStatusChangedListener(LoadStatusChangedListener { loadStatusChangedEvent ->

            //scene.basemap = Basemap.createImagery()
            mArView!!.clippingDistance = 400.0 //1500.0 //5000.0 750
            // add the scene to the AR view's scene view
            //val test = scene?.basemap
            mArView!!.sceneView.scene = scene

            //mArView!!.elevation = 5F
            //mArView!!.sceneView.scene.basemap = Basemap.createTopographic()
            // set the base surface to fully opaque
            scene?.baseSurface!!.opacity = 0f
            // let the camera move below ground
            scene?.baseSurface!!.navigationConstraint = NavigationConstraint.NONE
            mHasConfiguredScene = true
            // set translation factor and origin camera for scene placement in AR
            updateTranslationFactorAndOriginCamera(scene, plane)
        })
    }

    private fun showLayers() {
        val adb: AlertDialog.Builder = AlertDialog.Builder(this@MainActivity)
        val iLayers: Int? = mArView?.sceneView?.getScene()?.getOperationalLayers()?.size
        val saLyrs = iLayers?.let { arrayOfNulls<String>(it) }
        val baLyrVis = iLayers?.let { BooleanArray(it) }

        if (iLayers != null) {
            for (iLyr in 0 until iLayers!!) {
                val lyr: Layer? = mArView?.sceneView?.getScene()?.getOperationalLayers()?.get(iLyr)

                saLyrs?.set(
                    iLyr,
                    if (lyr?.name != null) lyr.name else getString(R.string.unnamed_layer)
                )
                lyr?.isVisible?.let { baLyrVis?.set(iLyr, it) }
            }
            getLayoutInflater().inflate(
                android.R.layout.simple_list_item_multiple_choice,
                null, false
            )
            adb.setTitle(R.string.tb_btn_layers)
                .setMultiChoiceItems(saLyrs, baLyrVis, onLayerToggled)
                .show()
        }
        else{
            Toast.makeText(this, "Слои не найдены", Toast.LENGTH_SHORT).show()
        }
    }

    private val onLayerToggled =
        OnMultiChoiceClickListener { dialogInterface, i, b ->
            val lyr = mArView!!.sceneView.scene.operationalLayers[i]
            lyr.isVisible = b
        }


    /**
     * Load the scene's first layer and calculate its geographical width. Use the scene's width and ArCore's assessment
     * of the plane's width to set the AR view's translation transformation factor. Use the center of the scene, corrected
     * for elevation, as the origin camera's look at point.
     *
     * @param scene to display
     * @param plane detected by ArCore to which the scene should be pinned
     */
    private fun updateTranslationFactorAndOriginCamera(scene: ArcGISScene?, plane: Plane) {

        scene?.operationalLayers!![0]?.loadAsync()
        scene?.operationalLayers[0].addDoneLoadingListener {

            // get the scene extent
            val layerExtent: Envelope = scene.operationalLayers[0].fullExtent
            scene.operationalLayers[0].fullExtent.width

            Toast.makeText(
                this,
                "fullExtent.width: " + scene.operationalLayers[0].fullExtent.width,
                Toast.LENGTH_SHORT
            ).show()

            // calculate the width of the layer content in meters
            val width = GeometryEngine.lengthGeodetic(
                layerExtent,
                LinearUnit(LinearUnitId.METERS),
                GeodeticCurveType.GEODESIC
            )


            mArView!!.translationFactor = 800.0 //(2 * 1500.0) / 0.3 //width / plane.getExtentX()

            // find the center point of the scene content
            val centerPoint: Point = layerExtent.center
            // find the altitude of the surface at the center
            val elevationFuture: ListenableFuture<Double> = mArView!!.sceneView.scene.baseSurface.getElevationAsync(
                centerPoint
            )

            elevationFuture.addDoneListener {
                try {
                    val elevation: Double = elevationFuture.get()
                    Toast.makeText(this, "elevation: $elevation", Toast.LENGTH_SHORT).show()
                    // create a new origin camera looking at the bottom center of the scene
                    //mArView!!.setViewpointCamera()
                    mArView!!.originCamera = Camera(
                        Point(
                            centerPoint.x,
                            centerPoint.y,
                            8.813445091247559
                        ), 0.0, 90.0, 0.0
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting elevation at point: " + e.message)
                }
            }
            mArView!!.locationDataSource = null
        }
        //})
    }
    
    override fun onPause() {
        if (mArView != null) {
            mArView?.stopTracking()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (mArView != null) {
            mArView?.startTracking(ArcGISArView.ARLocationTrackingMode.INITIAL)
        }
    }
}
