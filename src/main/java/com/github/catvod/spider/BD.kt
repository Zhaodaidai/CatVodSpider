package com.github.catvod.spider

import cn.hutool.core.codec.Base64
import cn.hutool.crypto.Mode
import cn.hutool.crypto.Padding
import cn.hutool.crypto.digest.DigestUtil
import cn.hutool.crypto.symmetric.AES
import cn.hutool.http.HtmlUtil
import com.github.catvod.bean.Class
import com.github.catvod.bean.Result
import com.github.catvod.bean.Vod
import com.github.catvod.bean.Vod.VodPlayBuilder.PlayUrl
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderDebug
import com.github.catvod.net.OkHttp
import com.github.catvod.utils.Image
import com.github.catvod.utils.Json
import com.github.catvod.utils.ProxyVideo
import com.github.catvod.utils.Utils
import io.ktor.http.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.apache.commons.lang3.StringUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.*
import kotlin.random.Random


class BD: Spider() {

    override fun init() {
        val res = OkHttp.newCall("$host$ad", Utils.webHeaders(host))
        if(res.isSuccessful){
            val c = res.headers[com.google.common.net.HttpHeaders.SET_COOKIE]
            session = c ?: ""
        }else{
            SpiderDebug.log("Db初始化失败：$res")
        }
    }

    override fun homeContent(filter: Boolean): String {
        val string = OkHttp.string(host, Utils.webHeaders(host, session))
        val body = Jsoup.parse(string).body()
        val vodList = mutableListOf<Vod>()
        val cons = body.select("div[class*=page-header] ~ div > div[class*=row-cards]")
        for (con in cons) {
            getVodList(con, vodList)
        }
        return Result.string(classList, vodList)
    }

    private fun getVodList(
        con: Element,
        vodList: MutableList<Vod>
    ) {
        val cards = con.select("div[class*=card-link]:has(div[class*=ribbon-bookmark])")
        for (card in cards) {
            val vod = Vod()
            vod.setVodRemarks(card.select("div > div[class*=ribbon]").text())
            val cover = card.select("a[class*=cover]")
            vod.setVodId(cover.attr("href"))
            val img = cover.select("img")
            var pic = img.attr("data-src")
            if(pic.isEmpty()){
                pic = img.attr("src")
            }
            vod.setVodPic(
                Image.UrlHeaderBuilder(pic).referer(host).userAgent(Utils.CHROME)
                    .build()
            )
            vod.setVodName(card.select("h3[class*=card-title]").text())
            vodList.add(vod)
        }
    }

    override fun categoryContent(tid: String, pg: String, filter: Boolean, extend: HashMap<String, String>): String {
        val url = "$host$tid/$pg"
        val string = OkHttp.string(url, Utils.webHeaders(host, session))
        val parse = Jsoup.parse(string)
        val list = mutableListOf<Vod>()
        getVodList(parse, list)
        return Result.string(classList, list)
    }

    override fun detailContent(ids: MutableList<String>): String {
        val id = ids[0]
        reference = "$host$id"
        val string = OkHttp.string(reference, Utils.webHeaders(host, session))
        val document = Jsoup.parse(string)
        val card = document.select("div.card > div.card-body").first()!!
        val head = card.select("div.col")
        val vod = Vod()
        vod.setVodName(head.select("h1").text())
        vod.setVodRemarks(head.select("div > span").last()?.text() ?: "")
        val img = card.select("div[class*=cover] > img").attr("src")
        vod.setVodPic(img)
        var detail = card.select("div.card-body > div[class*=row] > div[class*=col]").last()!!
        val removeDetail = HtmlUtil.removeHtmlTag(detail.html(), "strong")
        detail = Jsoup.parse(removeDetail)
        val pList = detail.select("p")
        val alia = pList[0].text()
        vod.setVodTag("")
        vod.setVodDirector(pList[1].text())
        vod.setVodActor(pList[3].select("a").joinToString(separator = " ") { it.text() })
        vod.setVodTag(pList[4].select("a").joinToString(separator = " ") { it.text() })
        vod.setVodArea(pList[5].text())
        vod.setVodYear(pList[7].text())

        vod.vodContent = document.select("div.card-body")[1].text()
        vod.vodContent += "\n$alia"

        val links = document.select("div#play-list a")
        val urlList = mutableListOf<PlayUrl>()
        for (link in links) {
            val playUrl = PlayUrl()
            playUrl.url = link.attr("href")
            playUrl.name = link.text()
            urlList.add(playUrl)
        }
        val buildResult = Vod.VodPlayBuilder().append("线路", urlList).build()
        vod.setVodPlayFrom(buildResult.vodPlayFrom)
        vod.vodPlayUrl = buildResult.vodPlayUrl

        return Result.string(vod)
    }

    // var js 变量
    private val pattern = Utils.base64Decode("dmFyICVzID0gKFxkKyk=")

    data class Resp(
        val code:Int,
        val data:Data,
        val msg:String
    )

    data class Data(
        val m3u8:String,
        val m3u8_2:String,
        val ptoken:String,
        val tos:String,
        val url3:String
    )

    private val bdFourHost = Utils.base64Decode("d3d3LmJkZTQuY2M=")
    override fun playerContent(flag: String, id: String, vipFlags: MutableList<String>): String {
        val s = "$host$id"
        val string = OkHttp.string(s, Utils.webHeaders(reference))
        val doc = Jsoup.parse(string)
        val sc = doc.select("script:containsData(pid)").html()
        val idRegex = String.format(pattern, "pid").toRegex()
        val pid = idRegex.find(sc)?.groups?.get(1)?.value ?: ""
        val time = Date().time.toString()
        val map = mutableMapOf("t" to time, "pid" to pid, "sg" to getSg(pid, time))
        val resp = OkHttp.string("${host}lines", map, Utils.webHeaders(reference))
        val res = Json.parseSafe<Resp>(resp, Resp::class.java)
        SpiderDebug.log("BD lines res:$resp")
        if(res.code != 0){
            SpiderDebug.log("Bd 播放失败")
            return ""
        }
        val urlList = mutableListOf<String>()
        if(StringUtils.isNotEmpty(res.data.url3)){
            urlList.add(res.data.url3)
        }
        if(StringUtils.isNotEmpty(res.data.tos)){
            urlList.add("${host}god/$pid?type=1")
        }
        if(StringUtils.isNotEmpty(res.data.m3u8)){
            urlList.add(res.data.m3u8)
        }
        if(StringUtils.isNotEmpty(res.data.ptoken)){
            urlList.add("${host}god/$pid")
        }
        val urlBuilder = Result.UrlBuilder()
        for (i in 0 until urlList.size) {
            urlList[i] = urlList[i].replace(bdFourHost, host.toHttpUrl().host)
            val split = urlList[i].split("#")
            if(split.size > 1){
                urlBuilder.add(split[1], buildUrl(split[0], pid, session, s))
            }else{
                urlBuilder.add(i.toString(), buildUrl(urlList[i], pid, session, s))
            }
        }
        return Result.get().url(urlBuilder.build()).header(mutableMapOf(HttpHeaders.Referrer to s, HttpHeaders.Cookie to session)).string()
    }

    private fun buildUrl(url: String, id:String, session:String, ref:String):String{
        return "${Proxy.getProxyUrl()}?do=bd&url=${Utils.base64Encode(url)}&id=$id&session=${Utils.base64Encode(session)}&ref=${Utils.base64Encode(ref)}"
    }

    companion object{
        private var session:String = ""

        private val host = Utils.base64Decode("aHR0cHM6Ly93d3cueWp5cy5tZS8=")

        private val classList = Class.parseFromFormatStr(Utils.base64Decode("5Yqo5L2cPS9zL2Rvbmd6dW8m54ix5oOFPS9zL2FpcWluZybllpzliac9L3MveGlqdSbnp5Hlubs9L3Mva2VodWFuJuaBkOaAlj0vcy9rb25nYnUm5oiY5LqJPS9zL3poYW56aGVuZybmrabkvqA9L3Mvd3V4aWEm6a2U5bm7PS9zL21vaHVhbibliafmg4U9L3MvanVxaW5nJuWKqOeUuz0vcy9kb25naHVhJuaDiuaCmj0vcy9qaW5nc29uZyYzRD0vcy8zRCbngb7pmr49L3MvemFpbmFuJuaCrOeWkT0vcy94dWFueWkm6K2m5YyqPS9zL2ppbmdmZWkm5paH6Im6PS9zL3dlbnlpJumdkuaYpT0vcy9xaW5nY2h1biblhpLpmak9L3MvbWFveGlhbibniq/nvao9L3MvZmFuenVpJue6quW9lT0vcy9qaWx1JuWPpOijhT0vcy9ndXpodWFuZyblpYflubs9L3MvcWlodWFuJuWbveivrT0vcy9ndW95dSbnu7zoibo9L3Mvem9uZ3lpJuWOhuWPsj0vcy9saXNoaSbov5Dliqg9L3MveXVuZG9uZybljp/liJvljovliLY9L3MveXVhbmNodWFuZybnvo7liac9L3MvbWVpanUm6Z+p5YmnPS9zL2hhbmp1JuWbveS6p+eUteinhuWJpz0vcy9ndW9qdSbml6Xliac9L3MvcmlqdSboi7Hliac9L3MveWluZ2p1JuW+t+WJpz0vcy9kZWp1JuS/hOWJpz0vcy9lanUm5be05YmnPS9zL2JhanUm5Yqg5YmnPS9zL2ppYWp1Juilv+WJpz0vcy9zcGFuaXNoJuaEj+Wkp+WIqeWJpz0vcy95aWRhbGlqdSbms7Dliac9L3MvdGFpanUm5riv5Y+w5YmnPS9zL2dhbmd0YWlqdSbms5Xliac9L3MvZmFqdSbmvrPliac9L3MvYW9qdQ=="))

        private val ad = Utils.base64Decode("enp6eno=")

        private var reference = host
        fun proxyLocal(params: MutableMap<String, String>): Array<Any> {
            var url = Utils.base64Decode(params["url"] ?: "")
            val id = Utils.base64Decode(params["id"] ?: "")
            val cookie = Utils.base64Decode(params["session"] ?: "")
            val ref = Utils.base64Decode(params["ref"] ?: "")
            if(url.contains("god")){
                val t = Date().time
                val p = mutableMapOf("t" to t, "sg" to getSg(id, t.toString()), "verifyCode" to "888")
                val body = OkHttp.post("${host}god", Json.toJson(p), Utils.webHeaders(host, cookie)).body
                SpiderDebug.log("DB god req:$body")
                url = Json.get().toJsonTree(body).asJsonObject.get("url").asString
            }
            return ProxyVideo.ProxyRespBuilder.redirect(url)
        }

        fun getSg(id:String, time:String):String{
            val s = "$id-$time"
            val digest = DigestUtil.md5Hex(s).substring(IntRange(0, 15)).toByteArray()
            val aes = AES(Mode.ECB, Padding.PKCS5Padding, digest)
            return base64ToHex(Base64.encode(aes.encrypt(s.toByteArray())))
        }

        private fun base64ToHex(s:String): String {
            val decodedBytes = Base64.decode(s)
            val hexString = StringBuilder()
            for (byte in decodedBytes) {
                val hex = Integer.toHexString(0xFF and byte.toInt())
                if (hex.length == 1) {
                    hexString.append('0')
                }
                hexString.append(hex)
            }
            return hexString.toString().uppercase()
        }
    }
}