package com.example.smsforwarder

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.smsforwarder.databinding.ActivityActivationBinding
import com.example.smsforwarder.license.DeviceCode
import com.example.smsforwarder.license.LicenseCodec
import com.example.smsforwarder.license.LicenseStore
import kotlin.concurrent.thread

/**
 * 离线授权激活门。未授权不能进入主功能；校验全部在本机完成，不需要联网。
 * 文案只给买家视角的「原因 + 下一步」，不出现签名、算法、错误码、设备码之外的内部信息。
 */
class ActivationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActivationBinding
    private var lastCode = ""

    private val why: Map<Int, String> = mapOf(
        LicenseCodec.ERR_FORMAT to "这串码不完整",
        LicenseCodec.ERR_BASE32 to "这串码里有认不出的字符",
        LicenseCodec.ERR_VERSION to "这串码需要更新 App 之后才能用",
        LicenseCodec.ERR_KEYID to "这串码不是这个 App 的",
        LicenseCodec.ERR_SIGNATURE to "这串码不对，可能复制时缺了字符",
        LicenseCodec.ERR_APPID to "这串码不是这个 App 的",
        LicenseCodec.ERR_DEVICE to "这串码已经绑在别的手机上了",
        LicenseCodec.ERR_EXPIRED to "这份授权到期了",
        LicenseCodec.ERR_NOT_STARTED to "手机时间不对",
        LicenseCodec.ERR_CLOCK_TAMPER to "手机时间不对",
        LicenseCodec.ERR_STORAGE to "本机的激活记录坏了",
        LicenseCodec.ERR_TAMPERED to "这个安装包不是正版"
    )

    private val todo: Map<Int, String> = mapOf(
        LicenseCodec.ERR_FORMAT to "把卖家发你的那整串重新复制一遍，从头到尾都要。",
        LicenseCodec.ERR_BASE32 to "让卖家再发一遍，复制的时候别漏头尾。",
        LicenseCodec.ERR_VERSION to "先把 App 更新到最新版，再粘贴一次。",
        LicenseCodec.ERR_KEYID to "找卖家确认一下，让他按这个 App 重新发一串。",
        LicenseCodec.ERR_SIGNATURE to "让卖家重新发一串，复制的时候别漏头尾。",
        LicenseCodec.ERR_APPID to "这串是别的 App 的，找卖家按这个 App 重新发。",
        LicenseCodec.ERR_DEVICE to "把下面的设备码发给卖家，让他重新发一串。",
        LicenseCodec.ERR_EXPIRED to "找卖家续期，续完再粘贴新的一串。",
        LicenseCodec.ERR_NOT_STARTED to "打开手机「自动设置日期时间」，再试一次。",
        LicenseCodec.ERR_CLOCK_TAMPER to "把手机时间调回正常，再试一次。",
        LicenseCodec.ERR_STORAGE to "在系统设置里清除本 App 的数据，然后重新激活。",
        LicenseCodec.ERR_TAMPERED to "卸载后从官方渠道重新安装。"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 已经有效授权：直接进主功能，不再停留在激活页。
        if (LicenseStore.isActive(this)) {
            enterMain()
            return
        }

        binding = ActivityActivationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindDeviceCode()
        bindInput()
        refreshState("")
        binding.copyDevice.setOnClickListener { copyDeviceCode() }
        binding.activateBtn.setOnClickListener { tryActivate() }
        binding.resultBtn.setOnClickListener { onResultAction() }
    }

    private fun bindDeviceCode() {
        val device = DeviceCode.bytes(this)
        val human = DeviceCode.human(device)
        val sp = SpannableString(human)
        sp.setSpan(
            ForegroundColorSpan(getColor(R.color.kf_brand)),
            0, 19, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sp.setSpan(
            ForegroundColorSpan(getColor(R.color.kf_ink)),
            20, 23, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.deviceCode.text = sp
    }

    private fun bindInput() {
        binding.codeInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshState(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun refreshState(raw: String) {
        val clean = LicenseCodec.clean(raw)
        lastCode = clean
        val len = clean.length
        val ready = len == LicTextLen && clean.startsWith(LicMagic)

        when {
            len == 0 -> binding.codeCounter.text = "粘贴后会自动检查"
            len < LicTextLen -> binding.codeCounter.text = "已输入 $len / $LicTextLen"
            !clean.startsWith(LicMagic) -> binding.codeCounter.text = "这串码不完整，重新复制一次"
            else -> binding.codeCounter.text = "可以点激活了"
        }

        binding.activateBtn.isEnabled = ready
        val tint = if (ready) R.color.kf_brand else R.color.kf_btn_disabled
        binding.activateBtn.backgroundTintList =
            android.content.res.ColorStateList.valueOf(getColor(tint))
    }

    private fun copyDeviceCode() {
        val device = DeviceCode.bytes(this)
        val text = DeviceCode.format(device)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("device_code", text))
        Toast.makeText(this, "设备码已复制", Toast.LENGTH_SHORT).show()
    }

    private fun tryActivate() {
        showChecking()
        thread {
            val code = LicenseStore.activate(this, lastCode)
            runOnUiThread { showResult(code) }
        }
    }

    private fun showGate() {
        binding.gateContainer.visibility = android.view.View.VISIBLE
        binding.checkingContainer.visibility = android.view.View.GONE
        binding.resultContainer.visibility = android.view.View.GONE
        refreshState(binding.codeInput.text?.toString().orEmpty())
    }

    private fun showChecking() {
        binding.gateContainer.visibility = android.view.View.GONE
        binding.checkingContainer.visibility = android.view.View.VISIBLE
        binding.resultContainer.visibility = android.view.View.GONE
    }

    private fun showResult(err: Int) {
        binding.gateContainer.visibility = android.view.View.GONE
        binding.checkingContainer.visibility = android.view.View.GONE
        binding.resultContainer.visibility = android.view.View.VISIBLE

        if (err == LicenseCodec.ERR_OK) {
            binding.heroIcon.background = getDrawable(R.drawable.bg_kf_hero_ok)
            binding.heroIconImg.setImageResource(R.drawable.ic_check)
            binding.resultTitle.text = "授权已激活"
            binding.resultSub.text = "以后打开就能用。"
            binding.resultCard.visibility = android.view.View.VISIBLE
            binding.expireLabel.text = "授权期限"
            binding.expireValue.text = LicenseStore.expireText(this)
            binding.resultNote.text = "换手机或重装 App 后，把新的设备码发给卖家，重新激活一次即可。"
            binding.resultNote.setBackgroundColor(0x140B7A4B)
            binding.resultBtn.text = "开始使用"
            binding.resultBtn.backgroundTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.kf_brand))
        } else {
            binding.heroIcon.background = getDrawable(R.drawable.bg_kf_hero_bad)
            binding.heroIconImg.setImageResource(R.drawable.ic_cross)
            binding.resultTitle.text = "没有激活成功"
            binding.resultSub.text = why[err] ?: "没有激活成功"
            binding.resultCard.visibility = android.view.View.GONE
            binding.resultNote.text = "怎么办：" + (todo[err] ?: "重新粘贴一次；还是不行就联系卖家。")
            binding.resultNote.setBackgroundColor(0x14C93A16)
            binding.resultBtn.text = "重新粘贴授权码"
            binding.resultBtn.backgroundTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.kf_surface2))
            binding.resultBtn.setTextColor(getColor(R.color.kf_brand))
        }
    }

    private fun onResultAction() {
        if (LicenseStore.isActive(this)) {
            enterMain()
        } else {
            binding.resultBtn.setTextColor(getColor(R.color.kf_brand))
            showGate()
            binding.codeInput.requestFocus()
        }
    }

    private fun enterMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        private const val LicTextLen = 144
        private const val LicMagic = "LKY1"
    }
}
