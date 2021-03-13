package com.example.ar_gis.utility

import android.app.AlertDialog
import android.content.DialogInterface
import android.view.Gravity
import android.widget.Toast
import com.esri.arcgisruntime.layers.Layer
import com.esri.arcgisruntime.loadable.LoadStatusChangedListener
import com.esri.arcgisruntime.mapping.ArcGISScene
import com.esri.arcgisruntime.portal.Portal
import com.esri.arcgisruntime.portal.PortalItem
import com.example.ar_gis.R
import java.util.*

class PortalAGOL() {

    private var _mPortal: Portal? = null

    // для хранения списка сцен
    private var _webscenes: MutableList<PortalItem> = ArrayList()

    private var _layer: MutableList<Layer> = ArrayList()

    private var _scene: ArcGISScene? = null;

    // для хранения текущей сцены
    private var _sceneItem: PortalItem? = null

    var Layers : MutableList<Layer>
        get() = _layer
        private set(value) {
            if(value != null){
                _layer = value
            }
        }

    var webScenes : MutableList<PortalItem>
        get() = _webscenes
        set(value) {
            if(value != null){
                _webscenes = value
            }
        }

    var sceneCurrent : ArcGISScene?
        get() = _scene
        set(value) {
            if(value != null){
                _scene = value
            }
        }

    var sceneItem : PortalItem?
        get() = _sceneItem
        set(value) {
            if(value != null){
                _sceneItem = value
            }
        }

    val mPortal : Portal?
        get() = _mPortal

    fun selectSceneDialog(context: android.content.Context){
        if (_webscenes.size > 0) {
            val saWebscenes = arrayOfNulls<String>(_webscenes.size)

            for (i in _webscenes.indices)
                saWebscenes[i] = _webscenes[i].title

            val onWebsceneChosen = DialogInterface.OnClickListener { dialog, which ->
                sceneItem = _webscenes[which]
                loadWebscene(sceneItem)
            }

            AlertDialog.Builder(context)
                .setTitle(R.string.dlg_title_choose_webscene)
                .setItems(saWebscenes, onWebsceneChosen)
                .show()

        } else {
            val toast: Toast = Toast.makeText(
                context,
                R.string.msg_no_webscenes,
                Toast.LENGTH_LONG
            )
            toast.setGravity(Gravity.CENTER_VERTICAL, 0, 0)
            toast.show()
        }
    }

    private fun loadWebscene(currentScene: PortalItem?) {
        try {
            if(currentScene != null) {
                val scene = ArcGISScene(currentScene)
                scene.loadAsync()

                scene.addDoneLoadingListener {
                    Layers = scene.operationalLayers
                    sceneCurrent = scene
                }
            }
        } catch (e: Exception) {
            //MessageUtils.showToast(this, "Error loading webscene: " + e.getLocalizedMessage());
        }
    }

}