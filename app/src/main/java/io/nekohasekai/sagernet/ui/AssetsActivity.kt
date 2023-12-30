package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutAssetItemBinding
import io.nekohasekai.sagernet.databinding.LayoutAssetsBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import libcore.Libcore
import java.io.File
import java.io.FileWriter
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AssetsActivity : ThemedActivity() {

    lateinit var adapter: AssetAdapter
    lateinit var layout: LayoutAssetsBinding
    lateinit var undoManager: UndoSnackbarManager<File>
    lateinit var updating: LinearProgressIndicator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = LayoutAssetsBinding.inflate(layoutInflater)
        layout = binding
        setContentView(binding.root)

        updating = findViewById(R.id.action_updating)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.route_assets)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        binding.recyclerView.layoutManager = FixedLinearLayoutManager(binding.recyclerView)
        adapter = AssetAdapter()
        binding.recyclerView.adapter = adapter

        binding.refreshLayout.setOnRefreshListener {
            adapter.reloadAssets()
            binding.refreshLayout.isRefreshing = false
        }
        binding.refreshLayout.setColorSchemeColors(getColorAttr(R.attr.primaryOrTextPrimary))

        undoManager = UndoSnackbarManager(this, adapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.START
        ) {

            override fun getSwipeDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val index = viewHolder.bindingAdapterPosition
                if (index < 2) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                adapter.remove(index)
                undoManager.remove(index to (viewHolder as AssetHolder).file)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

        }).attachToRecyclerView(binding.recyclerView)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(layout.coordinator, text, Snackbar.LENGTH_LONG)
    }

//    val assetNames = listOf("geoip", "geosite")

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.asset_menu, menu)
        return true
    }


    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            if (file != null) {
                val fileName = contentResolver.query(file, null, null, null, null)?.use { cursor ->
                    cursor.moveToFirst()
                    cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                        .let(cursor::getString)
                }?.takeIf { it.isNotBlank() } ?: file.pathSegments.last().substringAfterLast('/')
                    .substringAfter(':')

                if (fileName.endsWith(".zip")) {
                    alert(getString(R.string.route_not_asset, fileName)).show()
                    return@registerForActivityResult
                }
                val filesDir = getExternalFilesDir(null) ?: filesDir

                runOnDefaultDispatcher {
                    val outFile = File(filesDir, fileName).apply {
                        parentFile?.mkdirs()
                    }

                    contentResolver.openInputStream(file)?.use(outFile.outputStream())

                    File(outFile.parentFile, outFile.nameWithoutExtension + ".version.txt").apply {
                        if (isFile) delete()
                        createNewFile()
                        val fw = FileWriter(this)
                        fw.write("Custom")
                        fw.close()
                    }

                    adapter.reloadAssets()
                }

            }
        }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
                return true
            }

            R.id.action_update_all -> {
                runOnDefaultDispatcher {
                    updateAsset()
                }
                return true
            }
        }
        return false
    }

    inner class AssetAdapter : RecyclerView.Adapter<AssetHolder>(),
        UndoSnackbarManager.Interface<File> {

        private val assets = ArrayList<File>()

        init {
            reloadAssets()
        }

        fun reloadAssets() {
            val filesDir = getExternalFilesDir(null) ?: filesDir
            val geoDir = File(filesDir, "geo")
            if (!geoDir.exists()) {
                geoDir.mkdirs()
            }
//            val files = filesDir.listFiles()
//                ?.filter { it.isFile && it.name.endsWith("version.txt") }
            assets.clear()
            assets.add(File(filesDir, "geoip.version.txt"))
            assets.add(File(filesDir, "geosite.version.txt"))
//            if (files != null) assets.addAll(files)

            updating.visibility = View.GONE

            layout.refreshLayout.post {
                notifyDataSetChanged()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetHolder {
            return AssetHolder(LayoutAssetItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: AssetHolder, position: Int) {
            holder.bind(assets[position])
        }

        override fun getItemCount(): Int {
            return assets.size
        }

        fun remove(index: Int) {
            assets.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, File>>) {
            for ((index, item) in actions) {
                assets.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, File>>) {
            val groups = actions.map { it.second }.toTypedArray()
            runOnDefaultDispatcher {
                groups.forEach { it.deleteRecursively() }
            }
        }

    }

//    val updating = AtomicInteger()

    inner class AssetHolder(val binding: LayoutAssetItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        lateinit var file: File

        fun bind(file: File) {
            this.file = file

            binding.assetName.text = file.name.removeSuffix(".version.txt")

            val localVersion = if (file.isFile) {
                file.readText().trim()
            } else {
                "Unknown"
            }

            binding.assetStatus.text = getString(R.string.route_asset_status, localVersion)

        }

    }

    private suspend fun updateAsset() {
        val filesDir = getExternalFilesDir(null) ?: filesDir


        val repos: List<String> = when (DataStore.rulesProvider) {
            0 -> listOf("SagerNet/sing-geoip", "SagerNet/sing-geosite")
            1 -> listOf("xchacha20-poly1305/sing-geoip", "xchacha20-poly1305/sing-geosite")
            2 -> listOf("Chocolate4U/Iran-sing-box-rules")
            else -> listOf("SagerNet/sing-geoip", "SagerNet/sing-geosite")
        }

        onMainDispatcher {
            updating.visibility = View.VISIBLE
        }

        val geoDir = File(filesDir, "geo")
        var cacheFiles: Array<File> = arrayOf()

        for ((list, repo) in repos.withIndex()) {

            val client = Libcore.newHttpClient().apply {
                modernTLS()
                keepAlive()
                trySocks5(DataStore.mixedPort)
                useCazilla(DataStore.enabledCazilla)
            }

            try {
                // https://codeload.github.com/SagerNet/sing-geosite/zip/refs/heads/rule-set
                val response = client.newRequest().apply {
                    setURL("https://codeload.github.com/$repo/zip/refs/heads/rule-set")
                }.execute()

                val cacheFile = File(filesDir.parentFile, filesDir.name + list + ".tmp")
                cacheFile.parentFile?.mkdirs()
                response.writeTo(cacheFile.canonicalPath)
                cacheFiles += cacheFile

                adapter.reloadAssets()

            } catch (e: Exception) {
                onMainDispatcher {
                    e.message?.let { snackbar(it).show() }
                }
            } finally {
                client.close()
            }
        }

        for (cacheFile in cacheFiles) {
            Libcore.unzipWithoutDir(cacheFile.absolutePath, geoDir.absolutePath)
            cacheFile.delete()
        }

        onMainDispatcher {
            snackbar(R.string.route_asset_updated).show()
            updating.visibility = View.GONE
        }

        val versionFileList: List<File> = listOf(
            File(filesDir, "geoip.version.txt"),
            File(filesDir, "geosite.version.txt")
        )
        for (versionFile in versionFileList) {
            versionFile.writeText(
                LocalDate.now()
                    .format(
                        DateTimeFormatter
                            .ofPattern("yyyyMMdd")
                    )
            )
        }
        adapter.reloadAssets()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onResume() {
        super.onResume()

        if (::adapter.isInitialized) {
            adapter.reloadAssets()
        }
    }


}