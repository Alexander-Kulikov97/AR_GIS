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
import android.net.Uri
import android.os.Bundle
import android.os.Environment.getExternalStorageDirectory
import android.preference.PreferenceManager
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.webkit.URLUtil
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
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
import com.esri.arcgisruntime.symbology.SceneSymbol
import com.esri.arcgisruntime.symbology.SimpleMarkerSceneSymbol
import com.esri.arcgisruntime.symbology.TextSymbol
import com.esri.arcgisruntime.toolkit.ar.ArLocationDataSource
import com.esri.arcgisruntime.toolkit.ar.ArcGISArView
import com.example.ar_gis.utility.Floor
import com.example.ar_gis.utility.PortalAGOL
import com.example.ar_gis.utility.Rooms
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.clans.fab.FloatingActionButton
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.ar.core.Plane
import java.util.*
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

    private var ActionIndoor:FloatingActionButton? = null
    private var ActionLayers:FloatingActionButton? = null
    private var ActionOpen:FloatingActionButton? = null
    private var ActionFile:FloatingActionButton? = null
    private var patchFile: String = ""

    private var bottomSheetBehavior: BottomSheetBehavior<View>? = null

    private var mMapView: MapView? = null

    private var mCallout: Callout? = null

    private var mIsCalibrating: Boolean = false

    private var floorList: MutableList<Floor> = arrayListOf()
    private var roomsList: MutableList<Rooms> = arrayListOf()



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mArView = findViewById(R.id.arView)
        mArView?.registerLifecycle(lifecycle)

        mMapView = findViewById(R.id.mapView)

        val map = ArcGISMap(Basemap.createOpenStreetMap())

        mMapView?.map = map

        mMapView?.setViewpoint(Viewpoint(54.724999797983116, 55.940950887826475, 1000.0))

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


        mMapView?.setOnTouchListener(object : DefaultMapViewOnTouchListener(this, mMapView) {
            override fun onSingleTapConfirmed(motionEvent: MotionEvent): Boolean {

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
                    "Высота: " + "м, Широта: " + java.lang.String.format(
                        "%.4f",
                        wgs84Point.y
                    ).toString() + ", Долгота: " + java.lang.String.format("%.4f", wgs84Point.x)
                )
                var lat = wgs84Point.y
                var long = wgs84Point.x
                var oldCamera = mArView?.originCamera

                val cam = Camera(
                    lat,
                    long,
                    oldCamera?.location?.z!!,
                    oldCamera.heading,
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
                mArView?.translationFactor = 1.0

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
                15f, entry.name, Color.RED, TextSymbol.HorizontalAlignment.LEFT,
                TextSymbol.VerticalAlignment.TOP
            ).apply {
                offsetY = 20f
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
        mArView?.translationFactor = 1.0

        mArView?.arSceneView?.planeRenderer?.isEnabled = false
        mArView?.arSceneView?.planeRenderer?.isVisible = false

        mPortalAGOL.sceneCurrent?.baseSurface?.opacity = 0f

        val calibrationButton: Button = findViewById(R.id.calibrateButton)
        calibrationButton.setOnClickListener{
            mIsCalibrating = !mIsCalibrating
            if (mIsCalibrating) {
                mPortalAGOL.sceneCurrent?.baseSurface?.opacity = 0.5f
            } else {
                mPortalAGOL.sceneCurrent?.baseSurface?.opacity = 0f
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
            mArView?.startTracking(ArcGISArView.ARLocationTrackingMode.INITIAL)
        }
    }
}
