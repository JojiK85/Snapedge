package com.snapedge.app.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.snapedge.app.R
import com.snapedge.app.model.SavedClip
import com.snapedge.app.ocr.SmartTextAnalyzer

class VaultAdapter(
    private val onCopyClick: (SavedClip) -> Unit,
    private val onPinClick: (SavedClip) -> Unit,
    private val onOpenUrlClick: (String) -> Unit
) : RecyclerView.Adapter<VaultAdapter.ClipViewHolder>() {

    private val items = mutableListOf<SavedClip>()

    fun submitList(newItems: List<SavedClip>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClipViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_vault_clip, parent, false)
        return ClipViewHolder(view)
    }

    override fun onBindViewHolder(holder: ClipViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ClipViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMeta: TextView = itemView.findViewById(R.id.tvClipMeta)
        private val tvContent: TextView = itemView.findViewById(R.id.tvClipContent)
        private val btnOpenLink: ImageButton = itemView.findViewById(R.id.btnVaultOpenLink)
        private val btnCopy: ImageButton = itemView.findViewById(R.id.btnVaultCopy)
        private val btnPin: ImageButton = itemView.findViewById(R.id.btnVaultPin)

        fun bind(clip: SavedClip) {
            val relTime = DateUtils.getRelativeTimeSpanString(
                clip.timestampMillis,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            tvMeta.text = "${clip.source} • $relTime"
            tvContent.text = clip.text

            val url = SmartTextAnalyzer.extractFirstUrl(clip.text)
            if (url != null) {
                btnOpenLink.visibility = View.VISIBLE
                btnOpenLink.setOnClickListener { onOpenUrlClick(url) }
            } else {
                btnOpenLink.visibility = View.GONE
            }

            btnCopy.setOnClickListener { onCopyClick(clip) }
            btnPin.setOnClickListener { onPinClick(clip) }
            itemView.setOnClickListener { onCopyClick(clip) }
        }
    }
}
