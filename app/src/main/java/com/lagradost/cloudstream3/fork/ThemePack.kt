package com.lagradost.cloudstream3.fork

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.fork.RemoteRules.Feature
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.toPx

/**
 * The theme pack: 100 themes (60 new, 40 from the admin panel), chosen in Plus > Temalar from
 * cards that show their colours, dark and light ones apart. Switched off with "theme_pack".
 */
object ThemePack {
    class T(
        val key: String, val name: String, val light: Boolean, val isNew: Boolean,
        val mode: Int, val overlay: Int,
        val bg: Int, val panel: Int, val text: Int, val accent: Int, val accent2: Int, val glow: Int, val glow2: Int,
    )

    fun available(context: Context?): Boolean = ForkFeatures.isOn(Feature.THEME_PACK, context)

    val all: List<T> = listOf(
        T("CtKomur", "Kömür", false, true, R.style.CtKomurMode, R.style.OverlayCtKomur, 0xFF0B0B0C.toInt(), 0xFF161618.toInt(), 0xFFF2F2F3.toInt(), 0xFFF2994A.toInt(), 0xFFC2571A.toInt(), 0xFF392718.toInt(), 0xFF23150E.toInt()),
        T("CtDevetuyu", "Deve Tüyü", false, true, R.style.CtDevetuyuMode, R.style.OverlayCtDevetuyu, 0xFF26211C.toInt(), 0xFF352E27.toInt(), 0xFFF3F2F1.toInt(), 0xFFC9A27A.toInt(), 0xFF8B6A45.toInt(), 0xFF473B2F.toInt(), 0xFF332A21.toInt()),
        T("CtAntrasit", "Antrasit", false, true, R.style.CtAntrasitMode, R.style.OverlayCtAntrasit, 0xFF0A0B0D.toInt(), 0xFF151719.toInt(), 0xFFF2F2F3.toInt(), 0xFF7DD3C0.toInt(), 0xFF3A9E8C.toInt(), 0xFF213331.toInt(), 0xFF101E1E.toInt()),
        T("CtEspresso", "Espresso", false, true, R.style.CtEspressoMode, R.style.OverlayCtEspresso, 0xFF0E0B09.toInt(), 0xFF1C1511.toInt(), 0xFFF4F2F0.toInt(), 0xFFD4A373.toInt(), 0xFF9C6644.toInt(), 0xFF36291E.toInt(), 0xFF201711.toInt()),
        T("CtSarap", "Şarap", false, true, R.style.CtSarapMode, R.style.OverlayCtSarap, 0xFF0F080A.toInt(), 0xFF1D1114.toInt(), 0xFFF4F0F1.toInt(), 0xFFC94F6D.toInt(), 0xFFE8A87C.toInt(), 0xFF34161E.toInt(), 0xFF2B1D19.toInt()),
        T("CtZeytin", "Zeytin", false, true, R.style.CtZeytinMode, R.style.OverlayCtZeytin, 0xFF0D0E09.toInt(), 0xFF191C12.toInt(), 0xFFF3F4F1.toInt(), 0xFFA8B545.toInt(), 0xFF6F7A26.toInt(), 0xFF2C2F15.toInt(), 0xFF1A1C0D.toInt()),
        T("CtPetrol", "Petrol", false, true, R.style.CtPetrolMode, R.style.OverlayCtPetrol, 0xFF070E10.toInt(), 0xFF0D1D21.toInt(), 0xFFF0F3F4.toInt(), 0xFF1FB5B8.toInt(), 0xFF0E6F7A.toInt(), 0xFF0C2F32.toInt(), 0xFF081B1E.toInt()),
        T("CtLacivertaltin", "Lacivert Altın", false, true, R.style.CtLacivertaltinMode, R.style.OverlayCtLacivertaltin, 0xFF060911.toInt(), 0xFF0D1221.toInt(), 0xFFF0F1F4.toInt(), 0xFFE0B85A.toInt(), 0xFF3B5BDB.toInt(), 0xFF322C20.toInt(), 0xFF0D142B.toInt()),
        T("CtMurdum", "Mürdüm", false, true, R.style.CtMurdumMode, R.style.OverlayCtMurdum, 0xFF0E090E.toInt(), 0xFF1C111C.toInt(), 0xFFF4F0F4.toInt(), 0xFFC77DBA.toInt(), 0xFF7E3A75.toInt(), 0xFF332030.toInt(), 0xFF1D0F1B.toInt()),
        T("CtBakirpatina", "Bakır Patina", false, true, R.style.CtBakirpatinaMode, R.style.OverlayCtBakirpatina, 0xFF090E0E.toInt(), 0xFF111D1D.toInt(), 0xFFF0F4F4.toInt(), 0xFFD9824F.toInt(), 0xFF3FB0A0.toInt(), 0xFF33251B.toInt(), 0xFF102321.toInt()),
        T("CtFildisigece", "Fildişi Gece", false, true, R.style.CtFildisigeceMode, R.style.OverlayCtFildisigece, 0xFF0D0C0A.toInt(), 0xFF191815.toInt(), 0xFFF3F3F1.toInt(), 0xFFEFE4CC.toInt(), 0xFFB9A77E.toInt(), 0xFF3A3731.toInt(), 0xFF232019.toInt()),
        T("CtDuman", "Duman", false, true, R.style.CtDumanMode, R.style.OverlayCtDuman, 0xFF201F23.toInt(), 0xFF2C2B31.toInt(), 0xFFF2F2F3.toInt(), 0xFFB9A7E8.toInt(), 0xFF7C6BB8.toInt(), 0xFF3F3A4A.toInt(), 0xFF2C2936.toInt()),
        T("CtTarcin", "Tarçın", false, true, R.style.CtTarcinMode, R.style.OverlayCtTarcin, 0xFF0F0A08.toInt(), 0xFF1F140F.toInt(), 0xFFF4F1F0.toInt(), 0xFFE07A3A.toInt(), 0xFFA3401A.toInt(), 0xFF392012.toInt(), 0xFF22110A.toInt()),
        T("CtHardal", "Hardal", false, true, R.style.CtHardalMode, R.style.OverlayCtHardal, 0xFF0E0D08.toInt(), 0xFF1D1A11.toInt(), 0xFFF4F3F0.toInt(), 0xFFE3B21B.toInt(), 0xFFA37A06.toInt(), 0xFF392E0C.toInt(), 0xFF211B08.toInt()),
        T("CtYosun", "Yosun", false, true, R.style.CtYosunMode, R.style.OverlayCtYosun, 0xFF0B0E09.toInt(), 0xFF151C12.toInt(), 0xFFF2F4F1.toInt(), 0xFF8FC25A.toInt(), 0xFF4E7F2A.toInt(), 0xFF253219.toInt(), 0xFF141D0D.toInt()),
        T("CtTurkuazmercan", "Turkuaz Mercan", false, true, R.style.CtTurkuazmercanMode, R.style.OverlayCtTurkuazmercan, 0xFF070E0F.toInt(), 0xFF0F1D1F.toInt(), 0xFFF0F4F4.toInt(), 0xFF19C3C8.toInt(), 0xFFFF7A59.toInt(), 0xFF0B3234.toInt(), 0xFF271C19.toInt()),
        T("CtMercan", "Mercan", false, true, R.style.CtMercanMode, R.style.OverlayCtMercan, 0xFF0E0A09.toInt(), 0xFF1C1312.toInt(), 0xFFF4F1F1.toInt(), 0xFFFF6F61.toInt(), 0xFFC93D32.toInt(), 0xFF3E1E1B.toInt(), 0xFF26110E.toInt()),
        T("CtKehribar", "Kehribar", false, true, R.style.CtKehribarMode, R.style.OverlayCtKehribar, 0xFF0F0C08.toInt(), 0xFF1E1810.toInt(), 0xFFF4F3F0.toInt(), 0xFFFFB000.toInt(), 0xFFD06A00.toInt(), 0xFF3F2D06.toInt(), 0xFF281807.toInt()),
        T("CtLeylakgecesi", "Leylak Gecesi", false, true, R.style.CtLeylakgecesiMode, R.style.OverlayCtLeylakgecesi, 0xFF211B28.toInt(), 0xFF2D2537.toInt(), 0xFFF2F1F4.toInt(), 0xFFC3A8F0.toInt(), 0xFF8A64D6.toInt(), 0xFF413750.toInt(), 0xFF2F243F.toInt()),
        T("CtMurekkep", "Mürekkep", false, true, R.style.CtMurekkepMode, R.style.OverlayCtMurekkep, 0xFF08090F.toInt(), 0xFF10121E.toInt(), 0xFFF0F1F4.toInt(), 0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF1F283D.toInt(), 0xFF1F1C2D.toInt()),
        T("CtCam", "Çam", false, true, R.style.CtCamMode, R.style.OverlayCtCam, 0xFF080F0C.toInt(), 0xFF101E17.toInt(), 0xFFF0F4F2.toInt(), 0xFF3FAE7A.toInt(), 0xFFD9B56F.toInt(), 0xFF132F22.toInt(), 0xFF232519.toInt()),
        T("CtKiraz", "Kiraz", false, true, R.style.CtKirazMode, R.style.OverlayCtKiraz, 0xFF0E0909.toInt(), 0xFF1C1113.toInt(), 0xFFF4F0F1.toInt(), 0xFFE0245E.toInt(), 0xFFF2B33D.toInt(), 0xFF380E1A.toInt(), 0xFF2C1F10.toInt()),
        T("CtGulkurusu", "Gül Kurusu", false, true, R.style.CtGulkurusuMode, R.style.OverlayCtGulkurusu, 0xFF261D1F.toInt(), 0xFF34272B.toInt(), 0xFFF3F1F2.toInt(), 0xFFD79AA8.toInt(), 0xFFA5647A.toInt(), 0xFF49363A.toInt(), 0xFF37262B.toInt()),
        T("CtArdic", "Ardıç", false, true, R.style.CtArdicMode, R.style.OverlayCtArdic, 0xFF090C0E.toInt(), 0xFF12181C.toInt(), 0xFFF1F3F4.toInt(), 0xFF73B3B3.toInt(), 0xFF4A7C8C.toInt(), 0xFF1E2D2F.toInt(), 0xFF111B1E.toInt()),
        T("CtKadife", "Kadife", false, true, R.style.CtKadifeMode, R.style.OverlayCtKadife, 0xFF0A070F.toInt(), 0xFF130F1F.toInt(), 0xFFF1F0F4.toInt(), 0xFF9D7BFF.toInt(), 0xFFE879B9.toInt(), 0xFF271E3F.toInt(), 0xFF271625.toInt()),
        T("CtKiremit", "Kiremit", false, true, R.style.CtKiremitMode, R.style.OverlayCtKiremit, 0xFF0E0A09.toInt(), 0xFF1C1412.toInt(), 0xFFF4F1F1.toInt(), 0xFFD0704A.toInt(), 0xFF8C3F22.toInt(), 0xFF351E16.toInt(), 0xFF1E110C.toInt()),
        T("CtMaun", "Maun", false, true, R.style.CtMaunMode, R.style.OverlayCtMaun, 0xFF100807.toInt(), 0xFF20110E.toInt(), 0xFFF4F1F0.toInt(), 0xFFC4502F.toInt(), 0xFFE0A060.toInt(), 0xFF34160F.toInt(), 0xFF2B1C13.toInt()),
        T("CtGumus", "Gümüş", false, true, R.style.CtGumusMode, R.style.OverlayCtGumus, 0xFF1E2124.toInt(), 0xFF2A2E32.toInt(), 0xFFF2F2F3.toInt(), 0xFFCBD6E2.toInt(), 0xFF8A9BB0.toInt(), 0xFF41454A.toInt(), 0xFF2C3136.toInt()),
        T("CtPlatin", "Platin", false, true, R.style.CtPlatinMode, R.style.OverlayCtPlatin, 0xFF0C0C0B.toInt(), 0xFF181716.toInt(), 0xFFF3F2F2.toInt(), 0xFFE6E0D4.toInt(), 0xFFA39D90.toInt(), 0xFF383633.toInt(), 0xFF201F1C.toInt()),
        T("CtObsidyen", "Obsidyen", false, true, R.style.CtObsidyenMode, R.style.OverlayCtObsidyen, 0xFF0B0A0D.toInt(), 0xFF16141A.toInt(), 0xFFF2F1F3.toInt(), 0xFFE4C590.toInt(), 0xFF9A7A4A.toInt(), 0xFF362F27.toInt(), 0xFF1E1915.toInt()),
        T("CtHasat", "Hasat", false, true, R.style.CtHasatMode, R.style.OverlayCtHasat, 0xFF24221E.toInt(), 0xFF322F29.toInt(), 0xFFF3F2F1.toInt(), 0xFFFABD2F.toInt(), 0xFFFB4934.toInt(), 0xFF4F4121.toInt(), 0xFF402721.toInt()),
        T("CtGunesgolgesi", "Güneş Gölgesi", false, true, R.style.CtGunesgolgesiMode, R.style.OverlayCtGunesgolgesi, 0xFF050F12.toInt(), 0xFF0A1E24.toInt(), 0xFFF0F3F4.toInt(), 0xFFD6A400.toInt(), 0xFF268BD2.toInt(), 0xFF2F2D0E.toInt(), 0xFF091F2B.toInt()),
        T("CtMoka", "Moka", false, true, R.style.CtMokaMode, R.style.OverlayCtMoka, 0xFF1A1A28.toInt(), 0xFF242438.toInt(), 0xFFF1F1F4.toInt(), 0xFFCBA6F7.toInt(), 0xFFF5C2E7.toInt(), 0xFF3D3651.toInt(), 0xFF363041.toInt()),
        T("CtGulcami", "Gül Çamı", false, true, R.style.CtGulcamiMode, R.style.OverlayCtGulcami, 0xFF0A090E.toInt(), 0xFF13111C.toInt(), 0xFFF1F0F4.toInt(), 0xFFEBBCBA.toInt(), 0xFFC4A7E7.toInt(), 0xFF372D30.toInt(), 0xFF221E2A.toInt()),
        T("CtEbediorman", "Ebedi Orman", false, true, R.style.CtEbediormanMode, R.style.OverlayCtEbediorman, 0xFF1E2421.toInt(), 0xFF2A322E.toInt(), 0xFFF2F3F2.toInt(), 0xFFA7C080.toInt(), 0xFFDBBC7F.toInt(), 0xFF394334.toInt(), 0xFF37382D.toInt()),
        T("CtDalga", "Dalga", false, true, R.style.CtDalgaMode, R.style.OverlayCtDalga, 0xFF0A0A0D.toInt(), 0xFF14141A.toInt(), 0xFFF1F1F3.toInt(), 0xFF7E9CD8.toInt(), 0xFFE6C384.toInt(), 0xFF212736.toInt(), 0xFF27221C.toInt()),
        T("CtAtom", "Atom", false, true, R.style.CtAtomMode, R.style.OverlayCtAtom, 0xFF1D2025.toInt(), 0xFF282C34.toInt(), 0xFFF1F2F3.toInt(), 0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF2B3D4D.toInt(), 0xFF332B3D.toInt()),
        T("CtAyu", "Ayu Gecesi", false, true, R.style.CtAyuMode, R.style.OverlayCtAyu, 0xFF080B0E.toInt(), 0xFF11171D.toInt(), 0xFFF0F2F4.toInt(), 0xFFFFB454.toInt(), 0xFF59C2FF.toInt(), 0xFF392D1C.toInt(), 0xFF13232D.toInt()),
        T("CtDevetuyuacik", "Deve Tüyü Açık", true, true, R.style.CtDevetuyuacikMode, R.style.OverlayCtDevetuyuacik, 0xFFF8F4EF.toInt(), 0xFFFEFEFE.toInt(), 0xFF29221A.toInt(), 0xFFA5774A.toInt(), 0xFF7A5530.toInt(), 0xFFEADFD3.toInt(), 0xFFEAE3DA.toInt()),
        T("CtKeten", "Keten", true, true, R.style.CtKetenMode, R.style.OverlayCtKeten, 0xFFF7F5F0.toInt(), 0xFFFEFEFE.toInt(), 0xFF27241B.toInt(), 0xFF4F7A5A.toInt(), 0xFF2F5A3D.toInt(), 0xFFDAE0D6.toInt(), 0xFFE1E4DC.toInt()),
        T("CtFildisi", "Fildişi", true, true, R.style.CtFildisiMode, R.style.OverlayCtFildisi, 0xFFF9F6EE.toInt(), 0xFFFEFEFE.toInt(), 0xFF292519.toInt(), 0xFF1F4E79.toInt(), 0xFF163A5C.toInt(), 0xFFD4D9DA.toInt(), 0xFFE0E1DE.toInt()),
        T("CtKagit", "Kâğıt", true, true, R.style.CtKagitMode, R.style.OverlayCtKagit, 0xFFF6F4F1.toInt(), 0xFFFEFEFE.toInt(), 0xFF26231D.toInt(), 0xFFC0392B.toInt(), 0xFF8E2A1F.toInt(), 0xFFEDD4CF.toInt(), 0xFFEBDEDA.toInt()),
        T("CtGulkurusuacik", "Gül Kurusu Açık", true, true, R.style.CtGulkurusuacikMode, R.style.OverlayCtGulkurusuacik, 0xFFF7F0F2.toInt(), 0xFFFEFEFE.toInt(), 0xFF271B1E.toInt(), 0xFFB5657A.toInt(), 0xFF8A4459.toInt(), 0xFFECD8DE.toInt(), 0xFFEBDDE1.toInt()),
        T("CtAdacayi", "Adaçayı", true, true, R.style.CtAdacayiMode, R.style.OverlayCtAdacayi, 0xFFF2F5F2.toInt(), 0xFFFEFEFE.toInt(), 0xFF1E241F.toInt(), 0xFF5F8A6B.toInt(), 0xFF3F6A4C.toInt(), 0xFFD9E3DB.toInt(), 0xFFDEE6E0.toInt()),
        T("CtOkyanuskopugu", "Okyanus Köpüğü", true, true, R.style.CtOkyanuskopuguMode, R.style.OverlayCtOkyanuskopugu, 0xFFF0F8F6.toInt(), 0xFFFEFEFE.toInt(), 0xFF1A2826.toInt(), 0xFF0E8F8A.toInt(), 0xFF08605D.toInt(), 0xFFCAE6E4.toInt(), 0xFFD6E7E5.toInt()),
        T("CtBulut", "Bulut", true, true, R.style.CtBulutMode, R.style.OverlayCtBulut, 0xFFEFF1F8.toInt(), 0xFFFEFEFE.toInt(), 0xFF191C29.toInt(), 0xFF4F6BED.toInt(), 0xFF3049C8.toInt(), 0xFFD4DAF6.toInt(), 0xFFDADFF3.toInt()),
        T("CtKumtasi", "Kum Taşı", true, true, R.style.CtKumtasiMode, R.style.OverlayCtKumtasi, 0xFFF8F3EF.toInt(), 0xFFFEFEFE.toInt(), 0xFF292119.toInt(), 0xFFC2703D.toInt(), 0xFF934D22.toInt(), 0xFFEFDDD1.toInt(), 0xFFEDE1D8.toInt()),
        T("CtLimon", "Limon", true, true, R.style.CtLimonMode, R.style.OverlayCtLimon, 0xFFFCFAEB.toInt(), 0xFFFFFEFD.toInt(), 0xFF292819.toInt(), 0xFFA07C00.toInt(), 0xFF6E5500.toInt(), 0xFFECE5C3.toInt(), 0xFFECE8D1.toInt()),
        T("CtSomon", "Somon", true, true, R.style.CtSomonMode, R.style.OverlayCtSomon, 0xFFFAEFED.toInt(), 0xFFFEFEFD.toInt(), 0xFF291C19.toInt(), 0xFFE2574C.toInt(), 0xFFB0372E.toInt(), 0xFFF6D5D2.toInt(), 0xFFF2DBD8.toInt()),
        T("CtKar", "Kar", true, true, R.style.CtKarMode, R.style.OverlayCtKar, 0xFFF0F3F7.toInt(), 0xFFFEFEFE.toInt(), 0xFF1C2027.toInt(), 0xFF5E81AC.toInt(), 0xFF3B5A85.toInt(), 0xFFD7E0EA.toInt(), 0xFFDCE2EA.toInt()),
        T("CtGunesisigi", "Güneş Işığı", true, true, R.style.CtGunesisigiMode, R.style.OverlayCtGunesisigi, 0xFFFCF7EB.toInt(), 0xFFFFFEFD.toInt(), 0xFF292519.toInt(), 0xFF268BD2.toInt(), 0xFFB58900.toInt(), 0xFFD8E5E7.toInt(), 0xFFF4EBD1.toInt()),
        T("CtLatte", "Latte", true, true, R.style.CtLatteMode, R.style.OverlayCtLatte, 0xFFF2F3F5.toInt(), 0xFFFEFEFE.toInt(), 0xFF1E2024.toInt(), 0xFF8839EF.toInt(), 0xFF1E66F5.toInt(), 0xFFE0D3F4.toInt(), 0xFFDBE3F5.toInt()),
        T("CtSafak", "Şafak", true, true, R.style.CtSafakMode, R.style.OverlayCtSafak, 0xFFFAF4ED.toInt(), 0xFFFEFEFE.toInt(), 0xFF292219.toInt(), 0xFFD7827E.toInt(), 0xFF907AA9.toInt(), 0xFFF4E1DA.toInt(), 0xFFEEE7E6.toInt()),
        T("CtZeytinacik", "Zeytin Açık", true, true, R.style.CtZeytinacikMode, R.style.OverlayCtZeytinacik, 0xFFF5F6F1.toInt(), 0xFFFEFEFE.toInt(), 0xFF24261C.toInt(), 0xFF6B7A2A.toInt(), 0xFF4A5518.toInt(), 0xFFDEE1CF.toInt(), 0xFFE2E4D9.toInt()),
        T("CtBal", "Bal", true, true, R.style.CtBalMode, R.style.OverlayCtBal, 0xFFFCF6EB.toInt(), 0xFFFFFEFD.toInt(), 0xFF292419.toInt(), 0xFFC98A00.toInt(), 0xFF8F6200.toInt(), 0xFFF3E4C3.toInt(), 0xFFF0E6D1.toInt()),
        T("CtYakut", "Yakut", true, true, R.style.CtYakutMode, R.style.OverlayCtYakut, 0xFFF6F1F1.toInt(), 0xFFFEFEFE.toInt(), 0xFF261C1D.toInt(), 0xFFC41E3A.toInt(), 0xFF8C1228.toInt(), 0xFFEDCDD2.toInt(), 0xFFEAD8DB.toInt()),
        T("CtLacivertacik", "Lacivert Açık", true, true, R.style.CtLacivertacikMode, R.style.OverlayCtLacivertacik, 0xFFF0F2F8.toInt(), 0xFFFEFEFE.toInt(), 0xFF1A1E28.toInt(), 0xFF1E3A8A.toInt(), 0xFF2563EB.toInt(), 0xFFCCD3E5.toInt(), 0xFFDAE2F7.toInt()),
        T("CtSutlukahve", "Sütlü Kahve", true, true, R.style.CtSutlukahveMode, R.style.OverlayCtSutlukahve, 0xFFF7F3F0.toInt(), 0xFFFEFEFE.toInt(), 0xFF27211C.toInt(), 0xFF6F4E37.toInt(), 0xFF4B3424.toInt(), 0xFFE0D7D1.toInt(), 0xFFE4DEDA.toInt()),
        T("CtOrkide", "Orkide", true, true, R.style.CtOrkideMode, R.style.OverlayCtOrkide, 0xFFF7F0F6.toInt(), 0xFFFEFEFE.toInt(), 0xFF271B26.toInt(), 0xFFA23B9C.toInt(), 0xFF6F2370.toInt(), 0xFFE9D1E7.toInt(), 0xFFE8D9E7.toInt()),
        T("CtCini", "Çini", true, true, R.style.CtCiniMode, R.style.OverlayCtCini, 0xFFEEF4F9.toInt(), 0xFFFEFEFE.toInt(), 0xFF192129.toInt(), 0xFF1565C0.toInt(), 0xFFC62828.toInt(), 0xFFC9DCEF.toInt(), 0xFFEADEE2.toInt()),
        T("CtDarknes", "Darknes Lord", false, false, R.style.CtDarknesMode, R.style.OverlayCtDarknes, 0xFF090A0F.toInt(), 0xFF12141D.toInt(), 0xFFF4F5FA.toInt(), 0xFFF04452.toInt(), 0xFFA91E30.toInt(), 0xFF30111B.toInt(), 0xFF171A38.toInt()),
        T("CtMidnight", "Midnight", false, false, R.style.CtMidnightMode, R.style.OverlayCtMidnight, 0xFF070B14.toInt(), 0xFF0F1626.toInt(), 0xFFEEF3FF.toInt(), 0xFF4F8CFF.toInt(), 0xFF2A5BD7.toInt(), 0xFF0F2350.toInt(), 0xFF1A1240.toInt()),
        T("CtEmerald", "Emerald", false, false, R.style.CtEmeraldMode, R.style.OverlayCtEmerald, 0xFF06100C.toInt(), 0xFF0E1A15.toInt(), 0xFFECFBF4.toInt(), 0xFF10B981.toInt(), 0xFF047857.toInt(), 0xFF0B3326.toInt(), 0xFF0F2A2E.toInt()),
        T("CtRoyal", "Royal Purple", false, false, R.style.CtRoyalMode, R.style.OverlayCtRoyal, 0xFF0B0714.toInt(), 0xFF150F24.toInt(), 0xFFF5F0FF.toInt(), 0xFF9B5CFF.toInt(), 0xFF6427D6.toInt(), 0xFF2B1352.toInt(), 0xFF14183D.toInt()),
        T("CtSunset", "Sunset", false, false, R.style.CtSunsetMode, R.style.OverlayCtSunset, 0xFF110A07.toInt(), 0xFF1C120D.toInt(), 0xFFFFF4EC.toInt(), 0xFFFF7A3D.toInt(), 0xFFD1441A.toInt(), 0xFF3D1A0C.toInt(), 0xFF341024.toInt()),
        T("CtCyberpunk", "Cyberpunk", false, false, R.style.CtCyberpunkMode, R.style.OverlayCtCyberpunk, 0xFF0A0612.toInt(), 0xFF140D22.toInt(), 0xFFFBF2FF.toInt(), 0xFFFF2BD6.toInt(), 0xFF7A2BFF.toInt(), 0xFF3A0C3D.toInt(), 0xFF0B2D40.toInt()),
        T("CtOcean", "Ocean", false, false, R.style.CtOceanMode, R.style.OverlayCtOcean, 0xFF05101A.toInt(), 0xFF0C1A27.toInt(), 0xFFEAF6FF.toInt(), 0xFF14B8C4.toInt(), 0xFF0E7490.toInt(), 0xFF0A2E44.toInt(), 0xFF0C1D3D.toInt()),
        T("CtGold", "Gold Luxe", false, false, R.style.CtGoldMode, R.style.OverlayCtGold, 0xFF0C0A06.toInt(), 0xFF17130C.toInt(), 0xFFFBF6EA.toInt(), 0xFFE8BD68.toInt(), 0xFFA7782A.toInt(), 0xFF33260C.toInt(), 0xFF1F1A0A.toInt()),
        T("CtNord", "Nord", false, false, R.style.CtNordMode, R.style.OverlayCtNord, 0xFF2E3440.toInt(), 0xFF3B4252.toInt(), 0xFFECEFF4.toInt(), 0xFF88C0D0.toInt(), 0xFF5E81AC.toInt(), 0xFF3B4A63.toInt(), 0xFF3A3F55.toInt()),
        T("CtDracula", "Dracula", false, false, R.style.CtDraculaMode, R.style.OverlayCtDracula, 0xFF1E1F29.toInt(), 0xFF282A36.toInt(), 0xFFF8F8F2.toInt(), 0xFFBD93F9.toInt(), 0xFFFF79C6.toInt(), 0xFF3A2A52.toInt(), 0xFF4A2340.toInt()),
        T("CtMonokai", "Monokai", false, false, R.style.CtMonokaiMode, R.style.OverlayCtMonokai, 0xFF1B1C17.toInt(), 0xFF272822.toInt(), 0xFFF8F8F2.toInt(), 0xFFA6E22E.toInt(), 0xFF66A10F.toInt(), 0xFF2B3313.toInt(), 0xFF3A1730.toInt()),
        T("CtRose", "Rose", false, false, R.style.CtRoseMode, R.style.OverlayCtRose, 0xFF12080C.toInt(), 0xFF1E0F16.toInt(), 0xFFFFF0F5.toInt(), 0xFFFF5D8F.toInt(), 0xFFC2185B.toInt(), 0xFF401427.toInt(), 0xFF2A1340.toInt()),
        T("CtMatrix", "Matrix", false, false, R.style.CtMatrixMode, R.style.OverlayCtMatrix, 0xFF020604.toInt(), 0xFF07110A.toInt(), 0xFFD6FFE0.toInt(), 0xFF22FF66.toInt(), 0xFF0D9E3A.toInt(), 0xFF06301A.toInt(), 0xFF03200F.toInt()),
        T("CtIce", "Ice", false, false, R.style.CtIceMode, R.style.OverlayCtIce, 0xFF071019.toInt(), 0xFF0F1C29.toInt(), 0xFFEFFAFF.toInt(), 0xFF7DD3FC.toInt(), 0xFF0EA5E9.toInt(), 0xFF0C3350.toInt(), 0xFF1A2A4D.toInt()),
        T("CtCrimson", "Crimson", false, false, R.style.CtCrimsonMode, R.style.OverlayCtCrimson, 0xFF0D0405.toInt(), 0xFF1A0A0C.toInt(), 0xFFFFF0F0.toInt(), 0xFFDC143C.toInt(), 0xFF7F0A1F.toInt(), 0xFF4A0A14.toInt(), 0xFF2A0A10.toInt()),
        T("CtAmoled", "AMOLED", false, false, R.style.CtAmoledMode, R.style.OverlayCtAmoled, 0xFF000000.toInt(), 0xFF0A0A0A.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF9A9A9A.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt()),
        T("CtGraphite", "Graphite", false, false, R.style.CtGraphiteMode, R.style.OverlayCtGraphite, 0xFF121316.toInt(), 0xFF1B1D21.toInt(), 0xFFF1F2F4.toInt(), 0xFFF59E0B.toInt(), 0xFFB45309.toInt(), 0xFF2A2620.toInt(), 0xFF1E2128.toInt()),
        T("CtLight", "Clean Light", true, false, R.style.CtLightMode, R.style.OverlayCtLight, 0xFFF4F5F9.toInt(), 0xFFFFFFFF.toInt(), 0xFF151826.toInt(), 0xFFE11D48.toInt(), 0xFFBE123C.toInt(), 0xFFFFE1E8.toInt(), 0xFFE0E7FF.toInt()),
        T("CtSepia", "Sepia", true, false, R.style.CtSepiaMode, R.style.OverlayCtSepia, 0xFFF3EAD8.toInt(), 0xFFFBF5E8.toInt(), 0xFF3B2F1E.toInt(), 0xFFA0522D.toInt(), 0xFF7A3B1D.toInt(), 0xFFEAD7B5.toInt(), 0xFFEFE0C4.toInt()),
        T("CtLavender", "Lavender", true, false, R.style.CtLavenderMode, R.style.OverlayCtLavender, 0xFFF3F1FB.toInt(), 0xFFFFFFFF.toInt(), 0xFF1F1838.toInt(), 0xFF7C3AED.toInt(), 0xFF5B21B6.toInt(), 0xFFE5DCFF.toInt(), 0xFFDCECFF.toInt()),
        T("CtForest", "Forest", false, false, R.style.CtForestMode, R.style.OverlayCtForest, 0xFF0A120B.toInt(), 0xFF121D14.toInt(), 0xFFEEF8EF.toInt(), 0xFF6ABF69.toInt(), 0xFF2E7D32.toInt(), 0xFF1B3A1E.toInt(), 0xFF12261A.toInt()),
        T("CtVolcano", "Volcano", false, false, R.style.CtVolcanoMode, R.style.OverlayCtVolcano, 0xFF120706.toInt(), 0xFF1F0E0B.toInt(), 0xFFFFF1EC.toInt(), 0xFFFF4D1A.toInt(), 0xFFB3260A.toInt(), 0xFF4A140A.toInt(), 0xFF3A1A08.toInt()),
        T("CtAurora", "Aurora", false, false, R.style.CtAuroraMode, R.style.OverlayCtAurora, 0xFF060B14.toInt(), 0xFF0E1626.toInt(), 0xFFEEF6FF.toInt(), 0xFF34E5C4.toInt(), 0xFF7C4DFF.toInt(), 0xFF0B3A3A.toInt(), 0xFF2A1450.toInt()),
        T("CtNeonblue", "Neon Blue", false, false, R.style.CtNeonblueMode, R.style.OverlayCtNeonblue, 0xFF03050F.toInt(), 0xFF0A0F22.toInt(), 0xFFEAF0FF.toInt(), 0xFF2F6BFF.toInt(), 0xFF00C2FF.toInt(), 0xFF0A1A5A.toInt(), 0xFF002A40.toInt()),
        T("CtToxic", "Toxic", false, false, R.style.CtToxicMode, R.style.OverlayCtToxic, 0xFF0A0D03.toInt(), 0xFF141A07.toInt(), 0xFFF5FFE0.toInt(), 0xFFC6FF00.toInt(), 0xFF7CB300.toInt(), 0xFF2A3A05.toInt(), 0xFF1A2A05.toInt()),
        T("CtBloodmoon", "Blood Moon", false, false, R.style.CtBloodmoonMode, R.style.OverlayCtBloodmoon, 0xFF0A0507.toInt(), 0xFF170B10.toInt(), 0xFFFDE9EE.toInt(), 0xFFFF3366.toInt(), 0xFF990033.toInt(), 0xFF3D0A1C.toInt(), 0xFF2A0A30.toInt()),
        T("CtCoffee", "Coffee", false, false, R.style.CtCoffeeMode, R.style.OverlayCtCoffee, 0xFF120D0A.toInt(), 0xFF1D1612.toInt(), 0xFFF6EDE6.toInt(), 0xFFC08A5B.toInt(), 0xFF8A5A35.toInt(), 0xFF3A2617.toInt(), 0xFF2A1F18.toInt()),
        T("CtMint", "Mint", false, false, R.style.CtMintMode, R.style.OverlayCtMint, 0xFF071311.toInt(), 0xFF0F1F1C.toInt(), 0xFFE8FFF9.toInt(), 0xFF3EEAC0.toInt(), 0xFF14A37F.toInt(), 0xFF0C3B33.toInt(), 0xFF0E2A30.toInt()),
        T("CtSynthwave", "Synthwave", false, false, R.style.CtSynthwaveMode, R.style.OverlayCtSynthwave, 0xFF140A1F.toInt(), 0xFF1F1030.toInt(), 0xFFFFF0FB.toInt(), 0xFFFF6AD5.toInt(), 0xFFFF9E44.toInt(), 0xFF4A1250.toInt(), 0xFF20124A.toInt()),
        T("CtSteel", "Steel", false, false, R.style.CtSteelMode, R.style.OverlayCtSteel, 0xFF0E1115.toInt(), 0xFF171B21.toInt(), 0xFFEEF2F6.toInt(), 0xFF94A3B8.toInt(), 0xFF64748B.toInt(), 0xFF1E2633.toInt(), 0xFF1A1F28.toInt()),
        T("CtOceandeep", "Ocean Deep", false, false, R.style.CtOceandeepMode, R.style.OverlayCtOceandeep, 0xFF020B12.toInt(), 0xFF061620.toInt(), 0xFFE5F6FF.toInt(), 0xFF0EA5E9.toInt(), 0xFF1E3A8A.toInt(), 0xFF052A40.toInt(), 0xFF0A1440.toInt()),
        T("CtSanddune", "Sand Dune", false, false, R.style.CtSandduneMode, R.style.OverlayCtSanddune, 0xFF14110B.toInt(), 0xFF1F1A12.toInt(), 0xFFF8F1E3.toInt(), 0xFFE9C46A.toInt(), 0xFFD48B3A.toInt(), 0xFF3A2F14.toInt(), 0xFF2E2010.toInt()),
        T("CtGalaxy", "Galaxy", false, false, R.style.CtGalaxyMode, R.style.OverlayCtGalaxy, 0xFF07051A.toInt(), 0xFF100C2A.toInt(), 0xFFF1EDFF.toInt(), 0xFF8B5CF6.toInt(), 0xFFEC4899.toInt(), 0xFF2A1260.toInt(), 0xFF3A0F40.toInt()),
        T("CtJade", "Jade", false, false, R.style.CtJadeMode, R.style.OverlayCtJade, 0xFF04100D.toInt(), 0xFF0B1D18.toInt(), 0xFFE6FFF6.toInt(), 0xFF00A86B.toInt(), 0xFF006B45.toInt(), 0xFF063A2A.toInt(), 0xFF08262A.toInt()),
        T("CtMidgold", "Midnight Gold", false, false, R.style.CtMidgoldMode, R.style.OverlayCtMidgold, 0xFF0A0B10.toInt(), 0xFF13151D.toInt(), 0xFFF5F3EC.toInt(), 0xFFD4AF37.toInt(), 0xFF8C6D1F.toInt(), 0xFF2A2310.toInt(), 0xFF161A30.toInt()),
        T("CtSakura", "Sakura", true, false, R.style.CtSakuraMode, R.style.OverlayCtSakura, 0xFFFDF2F5.toInt(), 0xFFFFFFFF.toInt(), 0xFF3A1D27.toInt(), 0xFFE75480.toInt(), 0xFFC2185B.toInt(), 0xFFFFD9E4.toInt(), 0xFFFFE9F0.toInt()),
        T("CtSky", "Sky", true, false, R.style.CtSkyMode, R.style.OverlayCtSky, 0xFFEEF6FD.toInt(), 0xFFFFFFFF.toInt(), 0xFF0F2238.toInt(), 0xFF0284C7.toInt(), 0xFF0369A1.toInt(), 0xFFD6EBFF.toInt(), 0xFFE0F2FE.toInt()),
        T("CtMintlight", "Mint Light", true, false, R.style.CtMintlightMode, R.style.OverlayCtMintlight, 0xFFEFFAF6.toInt(), 0xFFFFFFFF.toInt(), 0xFF10291F.toInt(), 0xFF059669.toInt(), 0xFF047857.toInt(), 0xFFD1FAE5.toInt(), 0xFFE0F7F1.toInt()),
        T("CtPeach", "Peach", true, false, R.style.CtPeachMode, R.style.OverlayCtPeach, 0xFFFFF4EC.toInt(), 0xFFFFFFFF.toInt(), 0xFF3A2214.toInt(), 0xFFF97316.toInt(), 0xFFC2410C.toInt(), 0xFFFFE1CC.toInt(), 0xFFFFEEDD.toInt()),
        T("CtSlatelight", "Slate Light", true, false, R.style.CtSlatelightMode, R.style.OverlayCtSlatelight, 0xFFF1F5F9.toInt(), 0xFFFFFFFF.toInt(), 0xFF0F172A.toInt(), 0xFF334155.toInt(), 0xFF0F172A.toInt(), 0xFFE2E8F0.toInt(), 0xFFE0E7FF.toInt()),
    )

    val styles: Map<String, Pair<Int, Int>> by lazy { all.associate { it.key to (it.mode to it.overlay) } }

    fun nameOf(key: String?): String? = all.firstOrNull { it.key == key }?.name

    // ---------------------------------------------------------------- never locked out

    private const val PREF_BOOTING = "fork_tp_booting"

    @Volatile
    private var bootChecked = false

    /**
     * Called before a theme is applied. When the last start with a theme of the pack never got
     * to the screen, that theme is taken back, so the app always opens again.
     * @return the theme to use.
     */
    fun guard(context: Context, themeName: String, themeKey: String): String {
        if (!styles.containsKey(themeName)) return themeName
        return try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (!bootChecked) {
                bootChecked = true
                if (prefs.getString(PREF_BOOTING, null) == themeName) {
                    prefs.edit().remove(themeKey).remove(PREF_BOOTING).commit()
                    return "AmoledLight"
                }
            }
            prefs.edit().putString(PREF_BOOTING, themeName).commit()
            themeName
        } catch (t: Throwable) {
            themeName
        }
    }

    /** The app is on screen: the theme works. */
    fun started(context: Context) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (prefs.contains(PREF_BOOTING)) prefs.edit().remove(PREF_BOOTING).apply()
        } catch (t: Throwable) {
            // nothing
        }
    }

    // ---------------------------------------------------------------- the glow of the corners

    /** The soft colour in the corners, like the admin panel, over the whole app. */
    private class Glow(private val a: Int, private val b: Int, private val light: Boolean) : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            val alpha = if (light) 0xB0 else 0x99
            val ca = (a and 0x00FFFFFF) or (alpha shl 24)
            val cb = (b and 0x00FFFFFF) or (alpha shl 24)
            paint.shader = RadialGradient(0f, 0f, maxOf(w, h) * 0.55f, ca, ca and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = RadialGradient(w, h * 0.18f, maxOf(w, h) * 0.42f, cb, cb and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Puts the glow of the chosen theme over the app, or takes it away (player, other themes). */
    fun glow(activity: Activity, show: Boolean = true) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
        try {
            val decor = activity.window?.decorView ?: return
            val key = PreferenceManager.getDefaultSharedPreferences(activity)
                .getString(activity.getString(R.string.app_theme_key), null)
            val t = all.firstOrNull { it.key == key }
            decor.foreground = if (show && t != null && available(activity)) Glow(t.glow, t.glow2, t.light) else null
        } catch (e: Throwable) {
            // nothing
        }
    }

    /** A small picture of a theme, like the cards of the admin panel. */
    private class Preview(context: Context, private val t: T) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = 9.toPx.toFloat()
            rect.set(0f, 0f, w, h)
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = t.bg
            canvas.drawRoundRect(rect, r, r, paint)
            paint.shader = RadialGradient(0f, 0f, w * 0.75f, t.glow, t.glow and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, r, r, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.toPx.toFloat()
            paint.color = 0x22888888
            canvas.drawRoundRect(rect, r, r, paint)
            paint.style = Paint.Style.FILL
            val p = 8.toPx.toFloat()
            // the dot
            val d = 14.toPx.toFloat()
            rect.set(p, p, p + d, p + d)
            paint.shader = LinearGradient(p, p, p + d, p + d, t.accent, t.accent2, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, 5.toPx.toFloat(), 5.toPx.toFloat(), paint)
            paint.shader = null
            // the panel
            val ph = 22.toPx.toFloat()
            rect.set(p, h - p - ph, w - p, h - p)
            paint.color = t.panel
            canvas.drawRoundRect(rect, 6.toPx.toFloat(), 6.toPx.toFloat(), paint)
            paint.style = Paint.Style.STROKE
            paint.color = 0x33888888
            canvas.drawRoundRect(rect, 6.toPx.toFloat(), 6.toPx.toFloat(), paint)
            paint.style = Paint.Style.FILL
            val cy = h - p - ph / 2
            val accW = 22.toPx.toFloat()
            rect.set(w - p - 6.toPx - accW, cy - 4.5f.toPx(), w - p - 6.toPx, cy + 4.5f.toPx())
            paint.color = t.accent
            canvas.drawRoundRect(rect, 5.toPx.toFloat(), 5.toPx.toFloat(), paint)
            rect.set(p + 6.toPx, cy - 2.toPx, w - p - 6.toPx - accW - 5.toPx, cy + 2.toPx)
            paint.color = (t.text and 0x00FFFFFF) or 0x80000000.toInt()
            canvas.drawRoundRect(rect, 3.toPx.toFloat(), 3.toPx.toFloat(), paint)
        }

        private fun Float.toPx(): Float = this * resources.displayMetrics.density
    }

    /** The picker, from the bottom: dark themes and light themes apart. */
    fun show(activity: Activity) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val themeKey = activity.getString(R.string.app_theme_key)
        val current = prefs.getString(themeKey, null)
        val dialog = BottomSheetDialog(activity)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val textColor = activity.colorFromAttribute(R.attr.textColor)
        val grayColor = activity.colorFromAttribute(R.attr.grayTextColor)
        val primary = activity.colorFromAttribute(R.attr.colorPrimary)

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.toPx, 6.toPx, 12.toPx, 24.toPx)
        }
        list.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(36.toPx, 4.toPx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, 4.toPx, 0, 12.toPx)
            }
            background = GradientDrawable().apply { setColor(grayColor); cornerRadius = 4.toPx.toFloat() }
            alpha = 0.5f
        })
        list.addView(TextView(activity).apply {
            text = activity.getString(R.string.tp_title)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(6.toPx, 0, 6.toPx, 2.toPx)
        })
        list.addView(TextView(activity).apply {
            text = activity.getString(R.string.tp_des)
            textSize = 13f
            setTextColor(grayColor)
            setPadding(6.toPx, 0, 6.toPx, 6.toPx)
        })

        fun pick(key: String?) {
            prefs.edit {
                if (key == null) remove(themeKey) else putString(themeKey, key)
                // The theme's own accent colour
                putString(activity.getString(R.string.primary_color_key), "Normal")
            }
            dialog.dismiss()
            activity.recreate()
        }

        fun section(title: String, items: List<T>) {
            list.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(6.toPx, 14.toPx, 6.toPx, 8.toPx)
                addView(TextView(activity).apply {
                    text = title
                    textSize = 14.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(textColor)
                })
                addView(TextView(activity).apply {
                    text = items.size.toString()
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(grayColor)
                    setPadding(8.toPx, 1.toPx, 8.toPx, 1.toPx)
                    background = GradientDrawable().apply {
                        cornerRadius = 20.toPx.toFloat()
                        setStroke(1.toPx, (grayColor and 0x00FFFFFF) or 0x55000000)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = 8.toPx }
                })
            })
            val columns = if (activity.resources.configuration.screenWidthDp >= 600) 5 else 3
            items.chunked(columns).forEach { rowItems ->
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                for (i in 0 until columns) {
                    val t = rowItems.getOrNull(i)
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(4.toPx, 4.toPx, 4.toPx, 4.toPx)
                    }
                    if (t == null) {
                        row.addView(View(activity), lp)
                        continue
                    }
                    val active = t.key == current
                    val card = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(7.toPx, 7.toPx, 7.toPx, 8.toPx)
                        isFocusable = true
                        isClickable = true
                        val normal = GradientDrawable().apply {
                            cornerRadius = 14.toPx.toFloat()
                            setColor(activity.colorFromAttribute(R.attr.boxItemBackground))
                            setStroke(if (active) 2.toPx else 1.toPx, if (active) primary else (grayColor and 0x00FFFFFF) or 0x33000000)
                        }
                        val focused = GradientDrawable().apply {
                            cornerRadius = 14.toPx.toFloat()
                            setColor(activity.colorFromAttribute(R.attr.boxItemBackground))
                            setStroke(2.toPx, textColor)
                        }
                        background = android.graphics.drawable.StateListDrawable().apply {
                            addState(intArrayOf(android.R.attr.state_focused), focused)
                            addState(intArrayOf(), normal)
                        }
                        setOnClickListener { pick(t.key) }
                    }
                    val previewBox = FrameLayout(activity)
                    previewBox.addView(Preview(activity, t), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 54.toPx))
                    if (t.isNew) {
                        previewBox.addView(TextView(activity).apply {
                            text = activity.getString(R.string.tp_new)
                            textSize = 8f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(Color.parseColor("#1A1300"))
                            setPadding(5.toPx, 1.toPx, 5.toPx, 1.toPx)
                            background = GradientDrawable().apply {
                                setColor(Color.parseColor("#FFD166"))
                                cornerRadius = 5.toPx.toFloat()
                            }
                        }, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.END
                        ).apply { setMargins(0, 6.toPx, 6.toPx, 0) })
                    }
                    card.addView(previewBox)
                    card.addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(1.toPx, 7.toPx, 1.toPx, 0)
                        addView(TextView(activity).apply {
                            text = t.name
                            textSize = 11.5f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(textColor)
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        if (active) addView(TextView(activity).apply {
                            text = activity.getString(R.string.tp_active)
                            textSize = 9.5f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(primary)
                        })
                    })
                    row.addView(card, lp)
                }
                list.addView(row)
            }
        }

        section(activity.getString(R.string.tp_dark), all.filter { !it.light }.sortedByDescending { it.isNew })
        section(activity.getString(R.string.tp_light), all.filter { it.light }.sortedByDescending { it.isNew })

        list.addView(TextView(activity).apply {
            text = activity.getString(R.string.tp_reset)
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(0, 14.toPx, 0, 14.toPx)
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = 14.toPx.toFloat()
                setStroke(1.toPx, (grayColor and 0x00FFFFFF) or 0x55000000)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(4.toPx, 16.toPx, 4.toPx, 0)
            }
            setOnClickListener { pick(null) }
        })

        val scroll = ScrollView(activity).apply {
            addView(list)
            setBackgroundColor(activity.colorFromAttribute(R.attr.primaryGrayBackground))
        }
        dialog.setContentView(scroll)
        dialog.show()
    }
}
