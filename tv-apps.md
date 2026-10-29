# Apps de la TV — Philips 2020/2021 UHD (TP Vision, MediaTek, Android 11)

Relevé du 20/10/2026 · **149 paquets** · APK 1164 Mo dont 417 Mo en /system · /data 2,9 Go / 4,0 Go (1,0 Go libre).

## 0. Lot du 29/10 — « ce qui n'est plus utilisé » (état actuel)

Relevé d'usage sur 6 semaines (`dumpsys usagestats`, section *yearly* : lancements, temps, dernière utilisation).
**99 paquets** · `/data` 1,96 Go / 4,0 Go → **2,13 Go libres (50 %)**.

| Paquet | Nom | Poids | Preuve d'inusage | Sort |
|---|---|---|---|---|
| `com.google.android.tts` | Synthèse vocale Google | 322 Mo (dont 230 de voix) | 0 lancement | retiré du profil (`install-existing` testé) |
| `fr.m6.m6replay` | M6+ | 137 Mo | 0 lancement depuis le 23/05 | désinstallé (splits sauvegardés) |
| `com.android.vending` | Play Store | 113 Mo | 3 min en 6 semaines | retiré du profil |
| `com.google.android.apps.mediashell` | Coquille recherche/Assistant | 115 Mo | 0 lancement | **gardé** : la recherche vocale en dépend |
| `com.valvesoftware.steamlink` | Steam Link | 90 Mo | 0 lancement, mis à jour tout seul le 28/10 | désinstallé (splits sauvegardés) |
| `com.amazon.amazonvideo.livingroom` | Prime Video | 68 Mo | 0 lancement | retiré du profil |
| `com.esaba.downloader` | Downloader | 51 Mo | 0 lancement | désinstallé (splits sauvegardés) |
| `com.wolf.google.lm` | Launcher Manager | 3 Mo | 0 lancement | désinstallé |

Sauvegardes : `/Volumes/Quick-Storage/Backup/free-my-tv/apps/{m6-5.118.0,steamlink-1.4.5,downloader-2.0.4,sdcard-downloader-folder}/`
(la dernière = les 4 APK que Downloader avait téléchargés dans `/sdcard/Download/Downloader`, 159 Mo).
Retour d'un paquet préinstallé : `adb shell cmd package install-existing --user 0 <pkg>` ; d'un paquet tiers :
`adb install-multiple base.apk split_config.*.apk`.

Ménage fait en même temps : `/sdcard` nettoyé des captures de test (`fmt_*.png`, `x.png`, `r.raw`, `*.xml`…) et du
dossier de Downloader (159 Mo) ; `pm trim-caches` a rendu 360 Mo de caches (Gboard, affiches Free My TV, SmartTube).

**Gardés exprès** : `mediashell` (recherche vocale), `flar2.homebutton` (Button Mapper, service d'accessibilité
actif — remap des touches de la télécommande), `com.google.android.tv.remote.service` (pilotage Home Assistant),
`com.mediatek.wwtv.tvcenter` (chaînes/HDMI), `org.droidtv.eum` + `com.android.tv.ota` (mises à jour), GMS/WebView/Gboard,
`tvlauncher` + `backdrop` (gelés, filet du mode Original).

## 1. Ce qui peut partir — promo, démos, doublons

| Paquet | Nom | APK | Version | État |
|---|---|---|---|---|
| `com.disney.disneyplus` | Disney+ | 38.4 Mo | 4.17.0+rc1-2025.09.30 | désactivée |
| `com.google.android.backdrop` | Veille Google (Backdrop) | 21.5 Mo | 2.3.5 | désactivée |
| `com.google.android.play.games` | Play Games | 18.5 Mo | 2025.05.61843 (809922015 | désactivée |
| `com.google.android.videos` | Google TV Films | 17.9 Mo | 4.40.15.23-tv | désactivée |
| `com.google.android.tvlauncher` | Lanceur Android TV | 13.0 Mo | 7.5.9-877845869-f | désactivée |
| `tv.wuaki.apptv` | Rakuten TV (préinstallé) | 12.7 Mo | 4.12.1-philips | active |
| `com.zdf.android.mediathek` | ZDF Mediathek | 10.7 Mo | 5.6 | désactivée |
| `uk.co.freeview.mdsclient` | Freeview (mdsclient) | 10.0 Mo | 2.0.424-b8764c9 | désactivée |
| `fusion.android.tv.demo` | Démo magasin | 9.7 Mo | 11 | désactivée |
| `de.swr.avp.ard.tv` | ARD Mediathek | 8.7 Mo | 8.5.2 | désactivée |
| `org.droidtv.nettv.market` | Philips: boutique d’apps | 8.3 Mo | 0.0.0.5 | désactivée |
| `com.google.android.tvrecommendations` | Recommandations ATV | 8.0 Mo | 7.4.0-848496343-f | active |
| `org.droidtv.nettvregistration` | Philips: enregistrement | 5.7 Mo | 5.0 | désactivée |
| `uk.co.freeview.fvpconfigauth` | Freeview (fvpconfigauth) | 5.4 Mo | 2.0.424-b8764c9 | désactivée |
| `uk.co.freeview.tifbridge` | Freeview (tifbridge) | 5.4 Mo | 2.0.424-b8764c9 | désactivée |
| `uk.co.freeview.stv` | Freeview (stv) | 2.9 Mo | 2.0.424-b8764c9 | désactivée |
| `uk.co.freeview.onnow` | Freeview (onnow) | 2.9 Mo | 2.0.424-b8764c9 | désactivée |
| `uk.co.freeview.ch5` | Freeview (ch5) | 2.9 Mo | 2.0.424-b8764c9 | désactivée |
| `uk.co.freeview.amc_horror` | Freeview (amc_horror) | 2.8 Mo | 2.0.424-b8764c9 | désactivée |
| `uk.co.freeview.uktv` | Freeview (uktv) | 2.8 Mo | 2.0.424-b8764c9 | désactivée |
| `uk.co.freeview.explore` | Freeview (explore) | 2.7 Mo | 2.0.424-b8764c9 | désactivée |
| `com.google.android.syncadapters.calendar` | Sync Google Agenda | 2.7 Mo | 2020.20.4-316065245-rele | active |
| `uk.co.freeview.itv` | Freeview (itv) | 2.7 Mo | 2.0.424-b8764c9 | désactivée |
| `com.google.android.youtube.tvmusic` | YouTube Music | 2.7 Mo | 1.12.06 | désactivée |
| `uk.co.freeview.amc_catchup` | Freeview (amc_catchup) | 2.7 Mo | 2.0.424-b8764c9 | désactivée |
| `uk.co.freeview.bbc_sounds` | Freeview (bbc_sounds) | 2.7 Mo | 2.0.424-b8764c9 | désactivée |
| `uk.co.freeview.bbc` | Freeview (bbc) | 2.7 Mo | 2.0.424-b8764c9 | désactivée |
| `uk.co.freeview.ch4_vod` | Freeview (ch4_vod) | 2.5 Mo | 2.0.424-b8764c9 | désactivée |
| `ru.ivi.client` | IVI | 2.4 Mo | 1.0 | désactivée |
| `com.google.android.feedback` | Retour d’infos Google | 0.7 Mo | 11-6626444 | active |
| `com.google.android.partnersetup` | Partner Setup | 0.5 Mo | 100.316795284 | active |
| `com.google.android.onetimeinitializer` | Initialiseur Google | 0.2 Mo | 11-6626444 | active |

**Total APK de ce lot : 234 Mo** — dont 4 installées dans /data (vraie suppression), les autres retirées du profil utilisateur (réversible).

## 2. Tes apps (installées par toi)

| Paquet | Nom | APK | Version | État |
|---|---|---|---|---|
| `tv.twitch.android.app` | Twitch | 56.0 Mo | 13.0.0.2 | active |
| `fr.m6.m6replay` | M6+ | 29.0 Mo | 5.118.0 | supprimée le 29/10 |
| `org.smarttube.stable` | SmartTube | 25.0 Mo | 32.10 | active |
| `com.esaba.downloader` | Downloader | 11.9 Mo | 2.0.4-ForGoogleAndroidDe | supprimée le 29/10 |
| `flar2.homebutton` | Button Mapper | 11.5 Mo | 4.12 | active |
| `app.zeniptv.mobile` | ZEN IPTV | 9.1 Mo | 2.1.0 | supprimée (remplacée par Free My TV) |
| `com.overdevs.at4k` | Lanceur AT4K | 9.1 Mo | 1.2 | supprimée (APK sauvegardés) |
| `com.limelight` | Moonlight | 5.3 Mo | 12.2 | active |
| `com.wolf.google.lm` | Launcher Manager | 1.5 Mo | 1.0.4 | supprimée le 29/10 |
| `io.freemytv` | — | 1.2 Mo | v2026.10.20-1208 | active |
| `com.valvesoftware.steamlink` | Steam Link | 90 Mo | 1.4.5 | supprimée le 29/10 |

## 3. Système — à ne jamais supprimer

| Paquet | Nom | APK | Version | État |
|---|---|---|---|---|
| `com.google.android.apps.mediashell` | Media Shell (TV) | 110.7 Mo | 3.72.446070 | active |
| `com.google.android.webview` | WebView | 98.4 Mo | 155.0.8059.43 | active |
| `com.google.android.gms` | Google Play Services | 88.0 Mo | 26.40.32 (180300-9956309 | active |
| `android` | Cadre Android | 29.8 Mo | 11 | active |
| `com.google.android.tts` | Synthèse vocale | 27.3 Mo | googletts.google-speech- | active |
| `com.android.tv.settings` | Paramètres Android TV | 26.1 Mo | 1.0 | active |
| `com.google.android.tungsten.setupwraith` | Assistant de configuration | 25.2 Mo | 1.0.973628820 | active |
| `com.google.android.inputmethod.latin` | Gboard | 24.9 Mo | 17.3.11.902587967-tv_rel | active |
| `com.android.systemui` | Interface système | 21.1 Mo | 11 | active |
| `org.droidtv.eum` | Philips: mises à jour | 20.5 Mo | T32_22_21_Q2R_EU_AP_v1_2 | active |
| `com.android.vending` | Google Play Store | 18.6 Mo | 53.0.12-24 [8] [PR] 9728 | active |
| `com.android.tv.settings.tpv` | Paramètres Philips | 18.1 Mo | 1.0 | active |
| `com.mediatek.wwtv.tvcenter` | MediaTek: tuner / chaînes | 17.1 Mo | 2.28.5 build 18321512 | active |
| `com.mediatek.tv.freeviewplay` | MediaTek: Freeview Play | 14.8 Mo | 1.0 | active |
| `com.mediatek.wwtv.mediaplayer` | MediaTek: lecteur média | 13.4 Mo | 2.28.2 build 18321812 | active |
| `com.google.android.permissioncontroller` | Autorisations | 12.7 Mo | aml_per_311810030 | active |
| `com.mediatek.tv.factory` | — | 12.6 Mo | 1.0.0.0 | active |
| `com.android.tv.ota` | Mise à jour système | 12.0 Mo | 1.0.0.0 | active |
| `com.mediatek.wwtv.setupwizard` | MediaTek: configuration | 9.8 Mo | 2.28.2 build 18328712 | active |
| `org.droidtv.nettv.market` | Philips: boutique d’apps | 8.3 Mo | 0.0.0.5 | désactivée |
| `com.android.bluetooth` | Bluetooth | 7.0 Mo | 11 | active |
| `com.tpv.xmic.ots.cms.client` | TPV: client CMS (hôtel) | 6.9 Mo | 0.6.04_ad | active |
| `com.google.android.gsf` | Google Services Framework | 6.5 Mo | 11-6626444 | active |
| `org.droidtv.nettvregistration` | Philips: enregistrement | 5.7 Mo | 5.0 | désactivée |
| `com.android.tv.tou` | Contrat d’utilisation | 5.4 Mo | 1.0.0.0 | active |
| `org.droidtv.icp` | Philips: installateur | 4.9 Mo | 5.0 | active |
| `com.google.android.tv.remote.service` | Télécommande virtuelle | 4.8 Mo | 7.00.999235495 | active |
| `com.mediatek.android.leanbacklauncher.partnercustomizer` | MediaTek: personnalisation lanceur | 3.6 Mo | 11 | active |
| `org.droidtv.ambisvc` | Ambilight | 3.6 Mo | 11 | active |
| `com.android.providers.media.module` | — | 3.0 Mo | 11 | active |
| `org.droidtv.candeebug` | Philips: journal CAN | 3.0 Mo | 9.7.0.42 | active |
| `com.mediatek.tv.service` | MediaTek: service TV | 2.7 Mo | 1.3 | active |
| `com.android.settings.intelligence` | — | 2.7 Mo | 11 | active |
| `org.droidtv.ui.strings` | Philips: textes | 2.1 Mo | 11 | active |
| `org.droidtv.tv.frameworklogger` | Philips: journaux | 2.1 Mo | 11 | active |
| `com.mediatek.tvinput` | — | 1.9 Mo | 11 | active |
| `com.android.providers.media` | — | 1.5 Mo | 11 | active |
| `org.droidtv.gel` | Philips: GEL | 1.1 Mo | 9.2.0.45 | active |
| `com.android.networkstack.inprocess` | — | 1.0 Mo | 11 | active |
| `com.mediatek.network` | — | 1.0 Mo | 2.1 | active |
| `com.mediatek.wwtv.webview` | MediaTek: WebView | 1.0 Mo | 1.0 | active |
| `com.android.providers.contacts` | — | 0.9 Mo | 11 | active |
| `com.android.providers.downloads` | — | 0.6 Mo | 11 | active |
| `com.android.networkstack.tethering.inprocess` | — | 0.5 Mo | 11 | active |
| `com.android.providers.calendar` | — | 0.5 Mo | 11 | active |
| `com.android.wifi.resources` | — | 0.4 Mo | R-initial | active |
| `com.android.providers.settings` | — | 0.3 Mo | 11 | active |
| `com.android.providers.tv` | Fournisseur TV | 0.1 Mo | 11 | active |
| `com.mediatek.android.boot.appsplashscreen` | — | 0.0 Mo | 11 | active |
| `com.mstar.android.tv.disclaimercustomization` | MStar: mentions légales | 0.0 Mo | 1.0 | active |
| `org.droidtv.tv.packageinfo` | Philips: infos paquets | 0.0 Mo | 1.0 | active |
| `com.android.providers.userdictionary` | — | 0.0 Mo | 11 | active |
| `android.auto_generated_rro_product__` | — | 0.0 Mo | 1.0 | active |
| `android.auto_generated_rro_vendor__` | — | 0.0 Mo | 1.0 | active |
| `android.autoinstalls.config.tpv.device` | — | 0.0 Mo | 1 | active |
| `com.android.networkstack.permissionconfig` | — | 0.0 Mo | 2019-09 | active |
| `com.android.bluetooth.auto_generated_rro_product__` | — | 0.0 Mo | 1.0 | active |
| `com.android.tv.settings.gms.resoverlay` | — | 0.0 Mo | 1.0 | active |
| `com.android.networkstack.tethering.inprocess.rro` | — | 0.0 Mo | 1.0 | active |
| `com.android.wifi.resources.rro` | — | 0.0 Mo | 1.0 | active |
| `com.android.providers.settings.auto_generated_rro_product__` | — | 0.0 Mo | 1.0 | active |
| `com.android.bluetooth.auto_generated_rro_vendor__` | — | 0.0 Mo | 1.0 | active |
| `com.android.providers.settings.auto_generated_rro_vendor__` | — | 0.0 Mo | 1.0 | active |
| `com.android.providers.contacts.auto_generated_rro_product__` | — | 0.0 Mo | 1.0 | active |
| `com.android.networkstack.tethering.rro` | — | 0.0 Mo | 1.0 | active |

## 4. Le reste (petits modules système)

| Paquet | Nom | APK | Version | État |
|---|---|---|---|---|
| `com.android.inputmethod.latin` | Clavier AOSP | 18.6 Mo | 11 | active |
| `com.google.android.katniss` | Assistant vocal | 17.1 Mo | 7.55.87+964183260.01 | active |
| `com.google.android.youtube.tv` | YouTube | 16.5 Mo | 6.54.320 | active |
| `com.google.android.marvin.talkback` | TalkBack | 10.9 Mo | 14.2.0.740629310 leanbac | active |
| `mediatek.factorymenu.ui` | MediaTek: menu usine | 10.0 Mo | 1.0 | active |
| `com.netflix.ninja` | Netflix | 6.6 Mo | 13.2.1 build 27207 | active |
| `com.amazon.amazonvideo.livingroom` | Prime Video | 5.4 Mo | 6.24.8+v16.0.0.361-allAb | active |
| `org.droidtv` | Philips: socle | 5.0 Mo | 11 | active |
| `com.google.android.packageinstaller` | — | 3.2 Mo | 11-7323543 | active |
| `com.google.android.ext.services` | Ext Services | 1.7 Mo | aml_ext_311812040 | active |
| `com.android.soundpicker` | — | 1.3 Mo | 11 | active |
| `com.android.printspooler` | Spouleur impression | 0.9 Mo | 11 | active |
| `com.android.captiveportallogin` | — | 0.4 Mo | 2019-09 | active |
| `com.android.shell` | Shell ADB | 0.3 Mo | 11 | active |
| `com.android.certinstaller` | — | 0.3 Mo | 11 | active |
| `com.google.android.sss.authbridge` | — | 0.3 Mo | 1.0.317482253 | active |
| `com.android.hotspot2.osulogin` | — | 0.2 Mo | 11 | active |
| `com.android.inputdevices` | — | 0.2 Mo | 11 | active |
| `com.android.se` | — | 0.2 Mo | 11 | active |
| `com.android.backupconfirm` | — | 0.1 Mo | 11 | active |
| `com.android.vpndialogs` | — | 0.1 Mo | 11 | active |
| `com.android.keychain` | — | 0.1 Mo | 11 | active |
| `com.google.android.tv.frameworkpackagestubs` | — | 0.1 Mo | 1.0.1 | active |
| `com.android.statementservice` | — | 0.1 Mo | 1.0 | active |
| `com.android.companiondevicemanager` | — | 0.0 Mo | 11 | active |
| `com.android.dynsystem` | — | 0.0 Mo | 11 | active |
| `com.android.externalstorage` | — | 0.0 Mo | 11 | active |
| `com.android.dreams.basic` | — | 0.0 Mo | 11 | active |
| `com.android.cts.priv.ctsshim` | CTS (test) | 0.0 Mo | 11-6508977 | active |
| `com.google.android.modulemetadata` | — | 0.0 Mo | 2022-06-01 R | active |
| `com.android.htmlviewer` | — | 0.0 Mo | 11 | active |
| `com.android.localtransport` | — | 0.0 Mo | 11 | active |
| `com.android.location.fused` | — | 0.0 Mo | 11 | active |
| `com.android.proxyhandler` | — | 0.0 Mo | 11 | active |
| `com.android.wallpaperbackup` | — | 0.0 Mo | 11 | active |
| `com.android.pacprocessor` | — | 0.0 Mo | 11 | active |
| `com.android.sharedstoragebackup` | — | 0.0 Mo | 11 | active |
| `com.google.android.ext.shared` | — | 0.0 Mo | 1 | active |
| `com.google.android.overlay.modules.permissioncontroller` | — | 0.0 Mo | 1.0 | active |
| `com.google.android.overlay.modules.ext.services` | — | 0.0 Mo | 1.0 | active |
| `com.google.android.overlay.modules.permissioncontroller.forframework` | — | 0.0 Mo | 1.0 | active |
| `com.google.android.overlay.modules.modulemetadata.forframework` | — | 0.0 Mo | 1.0 | active |
| `com.android.cts.ctsshim` | CTS (test) | 0.0 Mo | 11-6508977 | active |
