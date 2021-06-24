package com.example.ar_gis

import android.Manifest
import android.app.AlertDialog
import android.app.Service
import android.content.DialogInterface
import android.content.DialogInterface.OnMultiChoiceClickListener
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment.getExternalStorageDirectory
import android.preference.PreferenceManager
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.webkit.URLUtil
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.esri.arcgisruntime.concurrent.ListenableFuture
import com.esri.arcgisruntime.geometry.GeometryEngine
import com.esri.arcgisruntime.geometry.Point
import com.esri.arcgisruntime.geometry.SpatialReferences
import com.esri.arcgisruntime.layers.FeatureLayer
import com.esri.arcgisruntime.layers.Layer
import com.esri.arcgisruntime.loadable.LoadStatus
import com.esri.arcgisruntime.location.LocationDataSource
import com.esri.arcgisruntime.mapping.*
import com.esri.arcgisruntime.mapping.view.*
import com.esri.arcgisruntime.portal.Portal
import com.esri.arcgisruntime.portal.PortalItem
import com.esri.arcgisruntime.portal.PortalUser
import com.esri.arcgisruntime.security.AuthenticationManager
import com.esri.arcgisruntime.security.DefaultAuthenticationChallengeHandler
import com.esri.arcgisruntime.symbology.TextSymbol
import com.esri.arcgisruntime.tasks.geocode.LocatorTask
import com.esri.arcgisruntime.tasks.geocode.ReverseGeocodeParameters
import com.esri.arcgisruntime.tasks.networkanalysis.RouteParameters
import com.esri.arcgisruntime.tasks.networkanalysis.RouteTask
import com.esri.arcgisruntime.toolkit.ar.ArLocationDataSource
import com.esri.arcgisruntime.toolkit.ar.ArcGISArView
import com.esri.arcgisruntime.toolkit.control.JoystickSeekBar
import com.esri.arcgisruntime.toolkit.control.JoystickSeekBar.DeltaProgressUpdatedListener
import com.example.ar_gis.utility.Floor
import com.example.ar_gis.utility.PortalAGOL
import com.example.ar_gis.utility.Rooms
import com.example.ar_gis.utility.Route
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.clans.fab.FloatingActionButton
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.bottom_sheet.*
import org.json.JSONException
import org.json.JSONObject
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt


class MainActivity : AppCompatActivity() {

    private val TAG = MainActivity::class.java.simpleName

    private var mHasConfiguredScene = false

    private var mArView: ArcGISArView? = null

    private var mPortal: Portal? = null

    private var mPortalAGOL: PortalAGOL = PortalAGOL();

    /** Высота для Уфы (может менятся в зависимости от местности) mCurrentVerticalOffset = 150 */
    private var mCurrentVerticalOffset = 1.0
    private var mAzimuth = 0.0 // degree
    private var mSensorManager: SensorManager? = null
    private var mAccelerometer: Sensor? = null
    private var mMagnetometer: Sensor? = null
    private var haveAccelerometer = false
    private var haveMagnetometer = false

    private var ActionIndoor:FloatingActionButton? = null
    private var ActionLayers:FloatingActionButton? = null
    private var ActionOpen:FloatingActionButton? = null
    private var ActionFile:FloatingActionButton? = null
    private var test:FloatingActionButton? = null
    private var qrCode: FloatingActionButton? = null
    private var btnScan: FloatingActionButton? = null

    private var patchFile: String = ""

    private var bottomSheetBehavior: BottomSheetBehavior<View>? = null

    private var mMapView: MapView? = null

    private var mCallout: Callout? = null

    private var mIsCalibrating: Boolean = false

    private var floorList: MutableList<Floor> = arrayListOf()
    private var roomsList: MutableList<Rooms> = arrayListOf()

    private var height = 178.8

    private var btnFloorMenu: Button? = null

    private val mMarkerGraphicsOverlay: GraphicsOverlay? = null
    private val mRouteGraphicsOverlay: GraphicsOverlay? = null
    private var mRouteTask: RouteTask? = null
    private var mRouteParameters: RouteParameters? = null
    private var mMobileMapPackage: MobileMapPackage? = null
    private var mMapView2: MapView? = null
    private var mMMPkTitle: String? = null
    private var mLocatorTask: LocatorTask? = null
    private val mReverseGeocodeParameters: ReverseGeocodeParameters? = null

    private var mCalibrationView: View? = null

    private var lat = 0.0
    private var lng = 0.0

    private var mTranslationFactor = 1.0 //0.25

    internal var qrScanIntegrator: IntentIntegrator? = null

    private var mLayerRooms: FeatureLayer? = null

    private var currentNumberRooms: String = ""

    private val locationDisplay: LocationDisplay? by lazy { mMapView?.locationDisplay }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScan = findViewById(R.id.btnScan)
        btnScan!!.setOnClickListener { performAction() }

        qrScanIntegrator = IntentIntegrator(this)
        qrScanIntegrator?.setOrientationLocked(false)


        mArView = findViewById(R.id.arView)
        mArView?.registerLifecycle(lifecycle)

        mMapView = findViewById(R.id.mapView)

        val portal = Portal("http://www.arcgis.com")
        val mapPortalItem = PortalItem(portal, "3ae7b71cf0d241e8ae29aa2bcf0fa3ad")
        val map = ArcGISMap(mapPortalItem)

        mMapView?.map = map

        //mMapView?.setViewpoint(Viewpoint(54.724999797983116, 55.940950887826475, 1000.0))

        val jsonSelectedFile =  contentResolver.openInputStream(
            Uri.parse(
                "file://" + getExternalStorageDirectory() + getString(
                    R.string.json_path
                )
            )
        );
        val inputAsString = jsonSelectedFile.bufferedReader().use { it.readText() }
        val mapper = jacksonObjectMapper()

        val list: MutableList<Floor> = mapper.readValue(inputAsString)
        floorList.addAll(list)

        val jsonRoomsFile =  contentResolver.openInputStream(
            Uri.parse(
                "file://" + getExternalStorageDirectory() + getString(
                    R.string.rooms_path
                )
            )
        );
        val roomsString = jsonRoomsFile.bufferedReader().use { it.readText() }

        val roomList: MutableList<Rooms> = mapper.readValue(roomsString)
        roomsList.addAll(roomList)

        ActionOpen = findViewById(R.id.floatingActionOpen) as FloatingActionButton
        ActionIndoor= findViewById(R.id.floatingActionIndoor) as FloatingActionButton
        ActionLayers = findViewById(R.id.floatingActionLayers) as FloatingActionButton
        ActionFile = findViewById(R.id.floatingActionFile) as FloatingActionButton

        ActionOpen!!.setOnClickListener { AuthAgol() }
        ActionIndoor!!.setOnClickListener { startIndoorArView() }
        ActionLayers!!.setOnClickListener { showLayers() }
        ActionFile!!.setOnClickListener { fileDialog() }

        mCalibrationView = findViewById(R.id.calibrationView);


        val popupMenu = PopupMenu(
            this,
            FloorMenu
        )

        floorList.forEach { floor ->
            popupMenu.menu.add(Menu.NONE, floor.floor, floor.floor, "Этаж: " + floor.floor)
        }

        popupMenu.setOnMenuItemClickListener { menuItem ->
            val id = menuItem.itemId
            btnFloor(id)

            false
        }

        FloorMenu.setOnClickListener {
            popupMenu.show()
        }

        val clearButton: ImageView =
            addressSearchView.findViewById(androidx.appcompat.R.id.search_close_btn)
        clearButton.setOnClickListener { v ->
            if (addressSearchView.getQuery().length === 0) {
                for (element in mPortalAGOL.LayerRoute) {
                    element.layer.isVisible = false
                }

                addressSearchView.setIconified(true)
            } else {

                for (element in mPortalAGOL.LayerRoute) {
                    element.layer.isVisible = false
                }
                // Do your task here
                addressSearchView.setQuery("", false)
            }
        }

        //locationDisplay?.autoPanMode = LocationDisplay.AutoPanMode.NAVIGATION
        //locationDisplay?.startAsync()

        addressSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                Log.i("on query: ", newText)

                for (element in mPortalAGOL.LayerRoute) {
                    element.layer.isVisible = false
                }

                if(newText != "" && newText.length >= 3) {
                    val filteredModelList: List<Route> = filter(mPortalAGOL.LayerRoute, newText)
                    for (element in filteredModelList) {
                        Log.i("user, ", element.roomsNumber)
                        element.layer.isVisible = true
                    }
                }


                return true
            }

            private fun filter(route: List<Route>, query: String): List<Route> {
                var query = query
                query = query.toLowerCase()
                val filteredModelList: MutableList<Route> = ArrayList()
                for (u in route) {
                    val text: String = u.roomsNumber
                    if (text.contains(query)) {
                        filteredModelList.add(u)
                    }
                }
                return filteredModelList
            }
        })

        val headingJoystick: JoystickSeekBar = findViewById(R.id.headingJoystick)
        // listen for calibration value changes for heading

        headingJoystick.addDeltaProgressUpdatedListener(object : DeltaProgressUpdatedListener {
            override fun onDeltaProgressUpdated(deltaProgress: Float) {
                // get the origin camera
                val camera = mArView!!.originCamera
                // add the heading delta to the existing camera heading
                val heading = camera.heading + deltaProgress
                // get a camera with a new heading
                val newCam = camera.rotateTo(heading, camera.pitch, camera.roll)
                // apply the new origin camera
                mArView!!.originCamera = newCam
            }
        })

        val altitudeJoystick: JoystickSeekBar = findViewById(R.id.altitudeJoystick)
        // listen for calibration value changes for altitude
        altitudeJoystick.addDeltaProgressUpdatedListener(object : DeltaProgressUpdatedListener {
            override fun onDeltaProgressUpdated(deltaProgress: Float) {
                mCurrentVerticalOffset += deltaProgress
                // get the origin camera
                val camera = mArView!!.originCamera
                // elevate camera by the delta
                val newCam = camera.elevate(deltaProgress.toDouble())
                // apply the new origin camera
                mArView!!.originCamera = newCam
            }
        })


//            object : SearchView?.OnQueryTextListener {
//            override fun onQueryTextSubmit(query: String?): Boolean {
//                return false
//            }
//
//            override fun onQueryTextChange(newText: String?): Boolean {
//
//                val text = newText
//                /*Call filter Method Created in Custom Adapter
//                    This Method Filter ListView According to Search Keyword
//                 */
//                customAdapter.filter(text)
//                return false
//            }
//        })


        mMapView?.setOnTouchListener(object : DefaultMapViewOnTouchListener(this, mMapView) {
            override fun onSingleTapConfirmed(motionEvent: MotionEvent): Boolean {

                val tappedPoint =
                    android.graphics.Point(motionEvent.x.roundToInt(), motionEvent.y.roundToInt())

                val tolerance = 25.0

                mMapView?.map?.operationalLayers?.forEach { layer ->
                    if (layer is FeatureLayer) {
                        if (layer.name.contains("placement") && layer.isVisible) {
                            mLayerRooms = layer
                        }
                    }
                }

//                val identifyLayerResultFuture =
//                    mapView.identifyLayerAsync(mLayerRooms, tappedPoint, tolerance, false, -1)
//
//                identifyLayerResultFuture.addDoneListener {
//                    try {
//                        val identifyLayerResult = identifyLayerResultFuture.get()
//                        // get the elements in the selection that are features
//                        val features = identifyLayerResult.elements.filterIsInstance<Feature>()
//                        // select each feature
//                        features.forEach { feature ->
//                            mLayerRooms?.selectFeature(feature)
//                        }
//
//                        val attr: Map<String, Any> = features[1].attributes
//                        currentNumberRooms = attr.get("number").toString() //["number"].toString()
//
//                    } catch (e: Exception) {
//                        val errorMessage = "Select feature failed: " + e.message
//                        Log.e(TAG, errorMessage)
//                        Toast.makeText(applicationContext, errorMessage, Toast.LENGTH_LONG).show()
//                    }
//                }

                // get the point that was clicked and convert it to a point in map coordinates
                val screenPoint = android.graphics.Point(
                    Math.round(motionEvent.x),
                    Math.round(motionEvent.y)
                )
                // create a map point from screen point
                val mapPoint = mMapView.screenToLocation(screenPoint)
                // convert to WGS84 for lat/lon format
                val wgs84Point =
                    GeometryEngine.project(mapPoint, SpatialReferences.getWgs84()) as Point

                // create a textview for the callout
                val calloutContent = TextView(applicationContext)
                calloutContent.setTextColor(Color.BLACK)
                calloutContent.setSingleLine()
                // format coordinates to 4 decimal places
                calloutContent.setText(
                    "Высота: " + height + "м, Широта: " + java.lang.String.format(
                        "%.4f",
                        wgs84Point.y
                    )
                        .toString() + ", Долгота: " + java.lang.String.format(
                        "%.4f",
                        wgs84Point.x
                    )
                )
                mCurrentVerticalOffset = height
                var lat = wgs84Point.y - 0.00001
                var long = wgs84Point.x - 0.00001
                var oldCamera = mArView?.originCamera

                val cam = Camera(
                    lat,
                    long,
                    height,
                    oldCamera?.heading!!,
                    oldCamera.pitch,
                    oldCamera.roll
                )
                mArView!!.originCamera = cam

                mArView!!.locationDataSource!!.addLocationChangedListener { locationChangedEvent: LocationDataSource.LocationChangedEvent ->
                    val updatedLocation = locationChangedEvent.location.position
                    mArView!!.originCamera = Camera(
                        Point(updatedLocation.x, updatedLocation.y, mCurrentVerticalOffset),
                        mAzimuth,
                        mArView!!.originCamera.pitch,
                        mArView!!.originCamera.roll
                    )

                    Toast.makeText(
                        this@MainActivity,
                        "Азимут: " + mAzimuth.toString() + " Высота: " + mCurrentVerticalOffset.toString() + " Z величина сцены " + updatedLocation.z,
                        Toast.LENGTH_LONG
                    ).show()
                }
                mArView!!.translationFactor = mTranslationFactor

                // get callout, set content and show
                mCallout = mMapView.callout
                mCallout?.setLocation(mapPoint)
                mCallout?.setContent(calloutContent)
                mCallout?.show()
                //mCallout.dismiss();

                // center on tapped point
                mMapView.setViewpointCenterAsync(mapPoint)
                return true
            }
        })
    }

    private fun performAction() {
        qrScanIntegrator?.initiateScan()
    }

    private fun btnFloor(floor: Int) {
        val iLayers = mMapView!!.map.operationalLayers.size
        val saLyrs = arrayOfNulls<String>(iLayers)
        var a = String()
        val fl = floor.toString() + "level"
        heightFloor(floor)
        val b = arrayOf(
            "window_",
            "wall_struct_",
            "wall_inter_",
            "stairs_",
            "door_",
            "placement_",
            "floor_"
        )
        val baLyrVis = BooleanArray(iLayers)
        for (iLyr in 0 until iLayers) {
            val lyr = mMapView!!.map.operationalLayers[iLyr]
            saLyrs[iLyr] = if (lyr.name != null) lyr.name else getString(R.string.unnamed_layer)
            a = if (lyr.name != null) lyr.name else getString(R.string.unnamed_layer)
            baLyrVis[iLyr] = lyr.isVisible

            lyr.isVisible = (a == b[0] + fl)
                    || (a == b[1] + fl)
                    || (a == b[2] + fl)
                    || (a == b[3] + fl)
                    || (a == b[4] + fl)
                    || (a == b[5] + fl)
                    || (a == b[6] + fl)
        }
    }

    private fun heightFloor(floor: Int) {
        height = floorList.first { f ->
            f.floor == floor
        }.elevation
    }

    private fun loadMobileMapPackage(path: String) {
        val mMobileScenePackage = MobileScenePackage(path)

        mMobileScenePackage.loadAsync()
        mMobileScenePackage.addDoneLoadingListener {
            // if it loaded successfully and the mobile scene package contains a scene
            if (mMobileScenePackage.getLoadStatus() === LoadStatus.LOADED && !mMobileScenePackage.getScenes().isEmpty()) {
                // get a reference to the first scene in the mobile scene package, which is of a section of philadelphia
                val scene: ArcGISScene = mMobileScenePackage.getScenes().get(0)
                scene.basemap = Basemap.createOpenStreetMap()

                mArView!!.sceneView.scene = scene

                addElevationSource(mArView?.sceneView?.scene)
                mArView?.locationDataSource = ArLocationDataSource(this)
                getLocation()
                mArView?.translationFactor = mTranslationFactor

                mArView?.arSceneView?.planeRenderer?.isEnabled = false
                mArView?.arSceneView?.planeRenderer?.isVisible = false

                Toast.makeText(this, "Сцена загружена", Toast.LENGTH_LONG).show()

            } else {
                val error =
                    "Failed to load mobile scene package: " + mMobileScenePackage.getLoadError().message
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                Log.e(TAG, error)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 111 && resultCode == RESULT_OK) {
            patchFile = "" + getExternalStorageDirectory() + "/" + data?.data?.path?.substringAfter(
                ':'
            )
            loadMobileMapPackage(patchFile)
        }

        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            // If QRCode has no data.
            if (result.contents == null) {
                Toast.makeText(this, getString(R.string.result_not_found), Toast.LENGTH_LONG).show()
            } else {
                // If QRCode contains data.
                try {
                    // Converting the data to json format
                    val obj = JSONObject(result.contents)

                    var lat = obj.getString("lat").toDouble()
                    var long = obj.getString("lng").toDouble()
                    var heading = obj.getString("heading").toDouble() //106 284

                    mCurrentVerticalOffset = obj.getString("elevation").toDouble()
                    height = mCurrentVerticalOffset

                    var oldCamera = mArView?.originCamera

                    val cam = Camera(
                        lat,
                        long,
                        height,
                        heading,
                        oldCamera!!.pitch,
                        oldCamera.roll
                    )
                    mArView!!.originCamera = cam

                    mArView!!.locationDataSource!!.addLocationChangedListener { locationChangedEvent: LocationDataSource.LocationChangedEvent ->
                        val updatedLocation = locationChangedEvent.location.position
                        mArView!!.originCamera = Camera(
                            Point(updatedLocation.x, updatedLocation.y, mCurrentVerticalOffset),
                            mAzimuth,
                            mArView!!.originCamera.pitch,
                            mArView!!.originCamera.roll
                        )

                        Toast.makeText(
                            this@MainActivity,
                            "Азимут: " + mAzimuth.toString() + " Высота: " + mCurrentVerticalOffset.toString() + " Z величина сцены " + updatedLocation.z,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    mArView!!.translationFactor = mTranslationFactor

                } catch (e: JSONException) {
                    e.printStackTrace()

                    // Data not in the expected format. So, whole object as toast message.
                    Toast.makeText(this, result.contents, Toast.LENGTH_LONG).show()
                }

            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun fileDialog() {
        val intent = Intent()
            .setType("*/*")
            .setAction(Intent.ACTION_GET_CONTENT)

        startActivityForResult(Intent.createChooser(intent, "Select a file"), 111)
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
                                            if (folderItem.type == PortalItem.Type.WEB_SCENE) {
                                                mPortalAGOL.webScenes.add(folderItem)
                                            }

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
    
    private fun startIndoorArView(){

        Toast.makeText(this, "Старт Indoor в Ar ", Toast.LENGTH_LONG).show()

        val drapedFlatOverlay = GraphicsOverlay().apply {
            sceneProperties.surfacePlacement = LayerSceneProperties.SurfacePlacement.ABSOLUTE
        }

        roomsList.forEach { entry ->
            val sceneRelatedPoint =
                Point(entry.lng, entry.lat, entry.elevation)
            val drapedFlatText = TextSymbol(
                20f, entry.name, Color.RED, TextSymbol.HorizontalAlignment.LEFT,
                TextSymbol.VerticalAlignment.TOP
            ).apply {
                offsetY = 0f
            }
            drapedFlatOverlay.
                graphics.addAll(
                arrayOf(
                    Graphic(sceneRelatedPoint, drapedFlatText)
                )
            )

        }

        mArView!!.sceneView.scene = mPortalAGOL.sceneCurrent
        
        mArView!!.sceneView.graphicsOverlays.addAll(
            arrayOf(
                drapedFlatOverlay
            )
        )



        addElevationSource(mArView?.sceneView?.scene)
        mArView?.locationDataSource = ArLocationDataSource(this)
        getLocation()
        mArView?.translationFactor = mTranslationFactor

        mArView?.arSceneView?.planeRenderer?.isEnabled = false
        mArView?.arSceneView?.planeRenderer?.isVisible = false

        mPortalAGOL.sceneCurrent?.baseSurface?.opacity = 0f

        if (mArView != null) {
            mArView?.startTracking(ArcGISArView.ARLocationTrackingMode.INITIAL)
        }

        val calibrationButton: FloatingActionButton = findViewById(R.id.calibrateButton)
        calibrationButton.setOnClickListener{
            mIsCalibrating = !mIsCalibrating
            if (mIsCalibrating) {
                mPortalAGOL.sceneCurrent?.baseSurface?.opacity = 0.5f
                mCalibrationView!!.setVisibility(View.VISIBLE);
            } else {
                mPortalAGOL.sceneCurrent?.baseSurface?.opacity = 0f
                mCalibrationView!!.setVisibility(View.GONE);
            }
        }
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
        surface.navigationConstraint = NavigationConstraint.STAY_ABOVE
        scene.baseSurface = surface
    }

    private fun requestPermissions() {
        // define permission to request
        val reqPermission =
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.CAMERA)
        val requestCode = 2
        if (ContextCompat.checkSelfPermission(
                this,
                reqPermission[0]
            ) == PackageManager.PERMISSION_GRANTED
        ) {

        } else {
            // request permission
            ActivityCompat.requestPermissions(this, reqPermission, requestCode)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String?>,
        grantResults: IntArray
    ) {
        if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

        } else {
            // report to user that permission was denied
            Toast.makeText(
                this,
                getString(R.string.tabletop_map_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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


    override fun onPause() {
        if (mArView != null) {
            mArView?.stopTracking()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (mArView != null) {
            mArView?.startTracking(ArcGISArView.ARLocationTrackingMode.IGNORE)
        }
    }
}
