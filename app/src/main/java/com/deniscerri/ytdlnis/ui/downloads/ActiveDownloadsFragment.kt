package com.deniscerri.ytdlnis.ui.downloads

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnClickListener
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.allViews
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkManager
import com.deniscerri.ytdlnis.R
import com.deniscerri.ytdlnis.adapter.ActiveDownloadAdapter
import com.deniscerri.ytdlnis.database.models.DownloadItem
import com.deniscerri.ytdlnis.database.repository.DownloadRepository
import com.deniscerri.ytdlnis.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdlnis.util.NotificationUtil
import com.deniscerri.ytdlnis.util.UiUtil.forceFastScrollMode
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext


class ActiveDownloadsFragment : Fragment(), ActiveDownloadAdapter.OnItemClickListener, OnClickListener {
    private var fragmentView: View? = null
    private var activity: Activity? = null
    private lateinit var downloadViewModel : DownloadViewModel
    private lateinit var activeRecyclerView : RecyclerView
    private lateinit var activeDownloads : ActiveDownloadAdapter
    lateinit var downloadItem: DownloadItem
    private lateinit var notificationUtil: NotificationUtil
    private lateinit var list: List<DownloadItem>
    private lateinit var pauseResume: MaterialButton


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        fragmentView = inflater.inflate(R.layout.fragment_active, container, false)
        activity = getActivity()
        notificationUtil = NotificationUtil(requireContext())
        downloadViewModel = ViewModelProvider(this)[DownloadViewModel::class.java]
        list = listOf()
        return fragmentView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activeDownloads =
            ActiveDownloadAdapter(
                this,
                requireActivity()
            )

        activeRecyclerView = view.findViewById(R.id.download_recyclerview)
        activeRecyclerView.forceFastScrollMode()
        activeRecyclerView.adapter = activeDownloads
        activeRecyclerView.layoutManager = GridLayoutManager(context, resources.getInteger(R.integer.grid_size))
        pauseResume = view.findViewById(R.id.pause_resume)

        pauseResume.setOnClickListener {
            if (pauseResume.text == requireContext().getString(R.string.pause)){

                lifecycleScope.launch {
                    runBlocking {
                        val queued = withContext(Dispatchers.IO){
                            downloadViewModel.getQueued()
                        }
                        queued.forEach {
                            WorkManager.getInstance(requireContext()).cancelUniqueWork(it.id.toString())
                        }
                    }

                    list.forEach {
                        cancelItem(it.id.toInt())
                        it.status = DownloadRepository.Status.Paused.toString()
                        downloadViewModel.updateDownload(it)
                    }

                    activeDownloads.notifyDataSetChanged()

                }
            }else{
                lifecycleScope.launch {
                    val toQueue = list.filter { it.status == DownloadRepository.Status.Paused.toString() }.toMutableList()
                    toQueue.forEach {
                        it.status = DownloadRepository.Status.Active.toString()
                        downloadViewModel.updateDownload(it)
                    }
                    val queuedItems = withContext(Dispatchers.IO){
                        downloadViewModel.getQueued()
                    }
                    toQueue.addAll(queuedItems)
                    runBlocking {
                        downloadViewModel.queueDownloads(toQueue)
                    }
                }
            }
        }

        downloadViewModel.activeDownloads.observe(viewLifecycleOwner) {
            list = it
            activeDownloads.submitList(it)

            if (it.size > 1){
                pauseResume.visibility = View.VISIBLE
                if (list.all { l -> l.status == DownloadRepository.Status.Paused.toString() }){
                    pauseResume.text = requireContext().getString(R.string.resume)
                }else{
                    pauseResume.text = requireContext().getString(R.string.pause)
                }
            }else{
                activeDownloads.notifyDataSetChanged()
                pauseResume.visibility = View.GONE
            }

            it.forEach{item ->
                WorkManager.getInstance(requireContext())
                    .getWorkInfosForUniqueWorkLiveData(item.id.toString())
                    .observe(viewLifecycleOwner){ list ->
                        list.forEach {work ->
                            if (work == null) return@observe
                            val id = work.progress.getLong("id", 0L)
                            if(id == 0L) return@observe

                            val progress = work.progress.getInt("progress", 0)
                            val output = work.progress.getString("output")

                            val progressBar = view.findViewWithTag<LinearProgressIndicator>("$id##progress")
                            val outputText = view.findViewWithTag<TextView>("$id##output")

                            requireActivity().runOnUiThread {
                                try {
                                    progressBar?.setProgressCompat(progress, true)
                                    outputText?.text = output
                                }catch (ignored: Exception) {}
                            }
                        }
                    }
            }
        }
    }

    override fun onCancelClick(itemID: Long) {
        cancelDownload(itemID)
    }

    override fun onPauseClick(itemID: Long, action: ActiveDownloadAdapter.ActiveDownloadAction) {
        val item = list.find { it.id == itemID } ?: return
        when(action){
            ActiveDownloadAdapter.ActiveDownloadAction.Pause -> {
                cancelItem(itemID.toInt())
                item.status = DownloadRepository.Status.Paused.toString()
                downloadViewModel.updateDownload(item)
            }
            ActiveDownloadAdapter.ActiveDownloadAction.Resume -> {
                item.status = DownloadRepository.Status.Active.toString()
                downloadViewModel.updateDownload(item)
                runBlocking {
                    downloadViewModel.queueDownloads(listOf(item))
                }
            }
        }

    }

    override fun onOutputClick(item: DownloadItem) {
        if (item.logID != null) {
            val bundle = Bundle()
            bundle.putLong("logID", item.logID!!)
            findNavController().navigate(
                R.id.downloadLogFragment,
                bundle
            )
        }
    }

    private fun cancelItem(id: Int){
        YoutubeDL.getInstance().destroyProcessById(id.toString())
        WorkManager.getInstance(requireContext()).cancelUniqueWork(id.toString())
        notificationUtil.cancelDownloadNotification(id)
    }

    private fun cancelDownload(itemID: Long){
        cancelItem(itemID.toInt())
        list.find { it.id == itemID }?.let {
            it.status = DownloadRepository.Status.Cancelled.toString()
            downloadViewModel.updateDownload(it)
        }

    }

    override fun onClick(p0: View?) {
        TODO("Not yet implemented")
    }
}