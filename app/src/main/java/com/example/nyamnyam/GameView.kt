package com.example.nyamnyam

import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.View
import kotlin.math.*
import kotlin.random.Random

/**
 * НЯМ-НЯМ: КОНФЕТНЫЙ ХАОС
 *
 * Оригинальная физическая puzzle-игра без сторонних движков и ассетов.
 * Все элементы рисуются Canvas'ом, поэтому проект легко собирается через GitHub Actions.
 *
 * Управление:
 *  - меню: START / LEVELS / SHOP / SETTINGS
 *  - в игре: потянуть и отпустить верёвку/кнопку CUT
 *  - пауза: верхняя правая кнопка
 *
 * Архитектура:
 *  - GameState отвечает за состояние
 *  - LevelData описывает уровень
 *  - Body хранит физическое тело
 *  - Rope хранит связь
 *  - GameView занимается рендером и вводом
 */
class GameView(ctx: Context) : View(ctx) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val prefs = ctx.getSharedPreferences("nyam_save", Context.MODE_PRIVATE)

    private enum class Screen { MENU, LEVELS, GAME, SHOP, SETTINGS }
    private var screen = Screen.MENU
    private var paused = false

    private var coins = prefs.getInt("coins", 120)
    private var bestLevel = prefs.getInt("best", 1)
    private var currentLevel = 1

    private var level: LevelData = makeLevel(1)
    private var candy = Body(0f, 0f, 24f)
    private var nyam = Body(0f, 0f, 45f)
    private val stars = mutableListOf<Star>()
    private val ropes = mutableListOf<Rope>()
    private val bubbles = mutableListOf<Bubble>()
    private val spikes = mutableListOf<Spike>()
    private val particles = mutableListOf<Particle>()

    private var cameraShake = 0f
    private var winTimer = 0f
    private var failTimer = 0f
    private var starsCollected = 0
    private var dragging = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val bgTop = Color.rgb(40, 20, 74)
    private val bgBottom = Color.rgb(255, 157, 132)

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        stroke.style = Paint.Style.STROKE
        stroke.strokeCap = Paint.Cap.ROUND
        isFocusable = true
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        try {
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            drawBackground(c, w, h)
            when (screen) {
                Screen.MENU -> drawMenu(c, w, h)
                Screen.LEVELS -> drawLevels(c, w, h)
                Screen.GAME -> drawGame(c, w, h)
                Screen.SHOP -> drawShop(c, w, h)
                Screen.SETTINGS -> drawSettings(c, w, h)
            }
        } catch (_: Throwable) {
            // Не даём редкому GPU/Canvas сбою закрыть приложение.
            c.drawColor(Color.rgb(25, 12, 40))
        }
        if (screen == Screen.GAME && !paused) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawBackground(c: Canvas, w: Float, h: Float) {
        p.shader = LinearGradient(0f, 0f, 0f, h, bgTop, bgBottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null

        // Луна/солнце и мягкие облака.
        p.color = Color.argb(90, 255, 236, 166)
        c.drawCircle(w * .78f, h * .16f, min(w, h) * .12f, p)

        p.color = Color.argb(35, 255, 255, 255)
        repeat(7) { i ->
            val x = (i * 173f + 80f) % (w + 180f) - 90f
            val y = h * (.12f + (i % 3) * .07f)
            c.drawOval(x, y, x + 150f, y + 32f, p)
        }

        // Декоративные конфетные домики.
        val horizon = h * .45f
        for (i in 0 until 9) {
            val x = i * w / 8f - 35f
            val bw = w * .14f
            val bh = h * (.12f + (i % 4) * .035f)
            p.color = Color.argb(130, 55, 28, 91)
            c.drawRoundRect(x, horizon - bh, x + bw, horizon, 16f, 16f, p)
            p.color = Color.argb(120, 255, 215, 247)
            for (yy in 0..1) for (xx in 0..1) {
                val wx = x + 18f + xx * 28f
                val wy = horizon - bh + 20f + yy * 30f
                c.drawRoundRect(wx, wy, wx + 13f, wy + 10f, 3f, 3f, p)
            }
        }
    }

    private fun drawMenu(c: Canvas, w: Float, h: Float) {
        // Logo
        text(c, "НЯМ-НЯМ", w * .5f, h * .16f, 44f, Color.WHITE, true, Paint.Align.CENTER)
        text(c, "КОНФЕТНЫЙ ХАОС", w * .5f, h * .21f, 19f, Color.rgb(255, 230, 110), true, Paint.Align.CENTER)

        drawCharacter(c, w * .5f, h * .37f, 1.15f)
        drawCandy(c, w * .73f, h * .38f, 1f)

        button(c, w*.5f, h*.56f, w*.72f, 62f, "ИГРАТЬ", Color.rgb(255, 73, 167))
        button(c, w*.5f, h*.65f, w*.72f, 52f, "УРОВНИ", Color.rgb(90, 70, 180))
        button(c, w*.5f, h*.73f, w*.72f, 52f, "МАГАЗИН", Color.rgb(55, 145, 205))
        button(c, w*.5f, h*.81f, w*.72f, 52f, "НАСТРОЙКИ", Color.rgb(70, 70, 105))

        pill(c, w*.16f, h*.07f, 116f, 42f, "🪙 $coins")
    }

    private fun drawLevels(c: Canvas, w: Float, h: Float) {
        title(c, "УРОВНИ", w, h)
        val cols = 3
        val size = min(w*.25f, 82f)
        for (i in 1..15) {
            val col = (i-1)%cols
            val row = (i-1)/cols
            val x = w*.2f + col*w*.3f
            val y = h*.25f + row*size*1.15f
            val unlocked = i <= bestLevel
            roundPanel(c, x-size*.42f, y-size*.42f, x+size*.42f, y+size*.42f,
                if (unlocked) Color.argb(225, 83, 46, 130) else Color.argb(150, 45, 35, 62))
            text(c, "$i", x, y+11f, 28f, if(unlocked) Color.WHITE else Color.GRAY, true, Paint.Align.CENTER)
            if (unlocked) {
                text(c, "★".repeat(if (i < bestLevel) 3 else 1), x, y+size*.62f, 15f,
                    Color.rgb(255,225,95), true, Paint.Align.CENTER)
            }
        }
        smallBack(c, w, h)
    }

    private fun drawShop(c: Canvas, w: Float, h: Float) {
        title(c, "МАГАЗИН", w, h)
        pill(c, w*.78f, h*.08f, 125f, 42f, "🪙 $coins")
        drawCharacter(c, w*.25f, h*.31f, 1.0f)
        text(c, "СКИН «ЯГОДКА»", w*.25f, h*.43f, 18f, Color.WHITE, true, Paint.Align.CENTER)
        button(c, w*.25f, h*.51f, 150f, 48f, "300 🪙", Color.rgb(255,73,167))

        drawCandy(c, w*.68f, h*.31f, 1.35f)
        text(c, "КОНФЕТНЫЙ", w*.68f, h*.43f, 18f, Color.WHITE, true, Paint.Align.CENTER)
        text(c, "МАГНИТ", w*.68f, h*.47f, 18f, Color.WHITE, true, Paint.Align.CENTER)
        button(c, w*.68f, h*.55f, 150f, 48f, "500 🪙", Color.rgb(55,145,205))
        smallBack(c, w, h)
    }

    private fun drawSettings(c: Canvas, w: Float, h: Float) {
        title(c, "НАСТРОЙКИ", w, h)
        text(c, "ГРАФИКА", w*.18f, h*.25f, 19f, Color.WHITE, true, Paint.Align.LEFT)
        option(c, w*.22f, h*.32f, "НИЗКАЯ")
        option(c, w*.50f, h*.32f, "СРЕДНЯЯ")
        option(c, w*.78f, h*.32f, "УЛЬТРА")
        text(c, "ЗВУК", w*.18f, h*.45f, 19f, Color.WHITE, true, Paint.Align.LEFT)
        option(c, w*.35f, h*.52f, "ВКЛ")
        option(c, w*.65f, h*.52f, "ВЫКЛ")
        text(c, "Сохранения хранятся на устройстве.", w*.5f, h*.66f, 16f, Color.argb(190,255,255,255), false, Paint.Align.CENTER)
        smallBack(c, w, h)
    }

    private fun drawGame(c: Canvas, w: Float, h: Float) {
        val ground = h * .73f
        drawPlayfield(c, w, h, ground)

        // Визуальная верёвка.
        for (r in ropes) {
            stroke.strokeWidth = 6f
            stroke.color = Color.rgb(92, 64, 74)
            c.drawLine(r.anchorX, r.anchorY, candy.x, candy.y, stroke)
            stroke.strokeWidth = 2f
            stroke.color = Color.argb(150, 255,255,255)
            c.drawLine(r.anchorX, r.anchorY, candy.x, candy.y, stroke)
            p.color = Color.rgb(55, 40, 62)
            c.drawCircle(r.anchorX, r.anchorY, 13f, p)
            p.color = Color.rgb(255, 94, 178)
            c.drawCircle(r.anchorX, r.anchorY, 7f, p)
        }

        for (b in bubbles) drawBubble(c, b)
        for (s in spikes) drawSpike(c, s)
        for (s in stars) if (!s.taken) drawStar(c, s.x, s.y, 16f)

        drawCandy(c, candy.x, candy.y, 1f)
        drawCharacter(c, nyam.x, nyam.y, 1f)

        for (pt in particles) {
            p.color = pt.color
            p.alpha = (255 * pt.life.coerceIn(0f,1f)).toInt()
            c.drawCircle(pt.x, pt.y, pt.size * pt.life, p)
            p.alpha = 255
        }

        // HUD
        pill(c, 72f, 38f, 112f, 40f, "УРОВЕНЬ $currentLevel")
        pill(c, w*.78f, 38f, 120f, 40f, "★ $starsCollected/3")
        roundPanel(c, 82f, h-70f, 228f, h-18f, Color.argb(210,35,18,55))
        text(c, "✂  ПЕРЕРЕЗАТЬ", 155f, h-38f, 17f, Color.WHITE, true, Paint.Align.CENTER)

        roundPanel(c, w-66f, 18f, w-18f, 66f, Color.argb(190,30,20,55))
        text(c, "Ⅱ", w-42f, 51f, 22f, Color.WHITE, true, Paint.Align.CENTER)

        if (paused) drawPause(c, w, h)
        if (winTimer > 0f) drawWin(c, w, h)
        if (failTimer > 0f) drawFail(c, w, h)
    }

    private fun drawPlayfield(c: Canvas, w: Float, h: Float, ground: Float) {
        // Candy-land платформы.
        p.color = Color.rgb(53, 29, 75)
        c.drawRect(0f, ground, w, h, p)
        for (i in 0..9) {
            val x = i*w/9f
            p.color = if(i%2==0) Color.rgb(71, 42, 91) else Color.rgb(61,35,82)
            c.drawRect(x, ground, x+w/9f+2, h, p)
        }
        p.color = Color.rgb(255, 116, 194)
        c.drawRect(0f, ground, w, ground+5f, p)

        // Вращающиеся candy stripes.
        val t = System.currentTimeMillis()/350f
        for(i in 0..7) {
            val x = (i*w/7f + (t*15)%w) % w
            p.color = Color.argb(100, 255, 225, 240)
            c.drawRoundRect(x, ground+18f, x+32f, ground+28f, 8f,8f,p)
        }
    }

    private fun drawPause(c: Canvas, w: Float, h: Float) {
        p.color = Color.argb(170, 20, 10, 35)
        c.drawRect(0f,0f,w,h,p)
        text(c,"ПАУЗА",w*.5f,h*.31f,38f,Color.WHITE,true,Paint.Align.CENTER)
        button(c,w*.5f,h*.46f,w*.66f,56f,"ПРОДОЛЖИТЬ",Color.rgb(255,73,167))
        button(c,w*.5f,h*.56f,w*.66f,50f,"ВЫЙТИ",Color.rgb(70,70,110))
    }

    private fun drawWin(c: Canvas, w: Float, h: Float) {
        p.color = Color.argb(185, 20, 10, 35); c.drawRect(0f,0f,w,h,p)
        text(c,"НЯМ!",w*.5f,h*.25f,48f,Color.rgb(255,225,95),true,Paint.Align.CENTER)
        text(c,"УРОВЕНЬ ПРОЙДЕН",w*.5f,h*.32f,22f,Color.WHITE,true,Paint.Align.CENTER)
        text(c,"★".repeat(starsCollected.coerceAtLeast(1)),w*.5f,h*.41f,38f,Color.rgb(255,225,95),true,Paint.Align.CENTER)
        button(c,w*.5f,h*.54f,w*.68f,56f,"СЛЕДУЮЩИЙ",Color.rgb(255,73,167))
        button(c,w*.5f,h*.63f,w*.68f,50f,"МЕНЮ",Color.rgb(70,70,110))
    }

    private fun drawFail(c: Canvas, w: Float, h: Float) {
        p.color = Color.argb(185,20,10,35); c.drawRect(0f,0f,w,h,p)
        text(c,"ОЙ!",w*.5f,h*.28f,48f,Color.rgb(255,120,155),true,Paint.Align.CENTER)
        text(c,"ПОПРОБУЙ ЕЩЁ РАЗ",w*.5f,h*.36f,22f,Color.WHITE,true,Paint.Align.CENTER)
        button(c,w*.5f,h*.52f,w*.68f,56f,"ПОВТОРИТЬ",Color.rgb(255,73,167))
        button(c,w*.5f,h*.61f,w*.68f,50f,"МЕНЮ",Color.rgb(70,70,110))
    }

    private fun drawCandy(c: Canvas, x: Float, y: Float, scale: Float) {
        p.setShadowLayer(18f*scale,0f,6f,Color.argb(120,255,74,175))
        p.color = Color.rgb(255, 78, 157)
        c.drawCircle(x,y,22f*scale,p)
        p.clearShadowLayer()
        p.color = Color.rgb(255, 218, 238)
        c.drawCircle(x-7f*scale,y-7f*scale,6f*scale,p)
        p.color = Color.rgb(255, 150, 204)
        c.drawCircle(x+6f*scale,y+6f*scale,11f*scale,p)
    }

    private fun drawCharacter(c: Canvas, x: Float, y: Float, scale: Float) {
        p.setShadowLayer(20f*scale,0f,8f,Color.argb(100,255,73,167))
        p.color = Color.rgb(74, 231, 174)
        c.drawOval(x-42f*scale,y-45f*scale,x+42f*scale,y+42f*scale,p)
        p.clearShadowLayer()
        // ушки
        p.color = Color.rgb(51, 190, 142)
        c.drawCircle(x-33f*scale,y-42f*scale,17f*scale,p)
        c.drawCircle(x+33f*scale,y-42f*scale,17f*scale,p)
        // глаза
        p.color = Color.WHITE
        c.drawCircle(x-15f*scale,y-12f*scale,11f*scale,p)
        c.drawCircle(x+15f*scale,y-12f*scale,11f*scale,p)
        p.color = Color.rgb(30,25,45)
        c.drawCircle(x-13f*scale,y-10f*scale,5f*scale,p)
        c.drawCircle(x+13f*scale,y-10f*scale,5f*scale,p)
        // рот
        stroke.strokeWidth=4f*scale; stroke.color=Color.rgb(45,40,60)
        c.drawArc(x-14f*scale,y+0f,x+14f*scale,y+21f*scale,0f,180f,false,stroke)
        // лапки
        p.color=Color.rgb(62,205,157)
        c.drawCircle(x-46f*scale,y+23f*scale,12f*scale,p)
        c.drawCircle(x+46f*scale,y+23f*scale,12f*scale,p)
    }

    private fun drawBubble(c: Canvas, b: Bubble) {
        p.color = Color.argb(70, 190, 240, 255)
        p.setShadowLayer(16f,0f,0f,Color.argb(100,90,220,255))
        c.drawCircle(b.x,b.y,b.r,p)
        p.clearShadowLayer()
        stroke.strokeWidth=2f; stroke.color=Color.argb(210,255,255,255)
        c.drawCircle(b.x,b.y,b.r,stroke)
        p.color=Color.argb(180,255,255,255)
        c.drawCircle(b.x-b.r*.32f,b.y-b.r*.32f,b.r*.18f,p)
    }

    private fun drawSpike(c: Canvas, s: Spike) {
        val path=Path()
        path.moveTo(s.x-s.size,s.y)
        path.lineTo(s.x,s.y-s.size*1.6f)
        path.lineTo(s.x+s.size,s.y)
        path.close()
        p.color=Color.rgb(255,85,122)
        p.setShadowLayer(10f,0f,0f,Color.rgb(255,55,110))
        c.drawPath(path,p)
        p.clearShadowLayer()
    }

    private fun drawStar(c: Canvas, x: Float, y: Float, r: Float) {
        val path=Path()
        for(i in 0 until 10) {
            val a=-PI/2+i*PI/5
            val rr=if(i%2==0) r else r*.45f
            val px=x+cos(a).toFloat()*rr
            val py=y+sin(a).toFloat()*rr
            if(i==0) path.moveTo(px,py) else path.lineTo(px,py)
        }
        path.close()
        p.color=Color.rgb(255,224,90)
        p.setShadowLayer(12f,0f,0f,Color.rgb(255,210,60))
        c.drawPath(path,p); p.clearShadowLayer()
    }

    private fun title(c:Canvas,title:String,w:Float,h:Float){
        text(c,title,w*.5f,h*.10f,30f,Color.WHITE,true,Paint.Align.CENTER)
    }

    private fun smallBack(c:Canvas,w:Float,h:Float){
        button(c,w*.5f,h*.90f,w*.48f,48f,"НАЗАД",Color.rgb(70,70,110))
    }

    private fun option(c:Canvas,x:Float,y:Float,s:String){
        roundPanel(c,x-55f,y-22f,x+55f,y+22f,Color.argb(190,55,42,92))
        text(c,s,x,y+6f,14f,Color.WHITE,true,Paint.Align.CENTER)
    }

    private fun button(c:Canvas,cx:Float,cy:Float,bw:Float,bh:Float,label:String,color:Int){
        p.setShadowLayer(12f,0f,5f,Color.argb(110,0,0,0))
        p.color=Color.argb(230,25,18,45)
        c.drawRoundRect(cx-bw/2,cy-bh/2,cx+bw/2,cy+bh/2,18f,18f,p)
        p.clearShadowLayer()
        p.color=color
        c.drawRoundRect(cx-bw/2+2,cy-bh/2+2,cx+bw/2-2,cy+bh/2-2,16f,16f,p)
        text(c,label,cx,cy+8f,20f,Color.WHITE,true,Paint.Align.CENTER)
    }

    private fun pill(c:Canvas,cx:Float,cy:Float,bw:Float,bh:Float,label:String){
        roundPanel(c,cx-bw/2,cy-bh/2,cx+bw/2,cy+bh/2,Color.argb(200,38,24,62))
        text(c,label,cx,cy+6f,15f,Color.WHITE,true,Paint.Align.CENTER)
    }

    private fun roundPanel(c:Canvas,l:Float,t:Float,r:Float,b:Float,color:Int){
        p.color=color
        c.drawRoundRect(l,t,r,b,16f,16f,p)
    }

    private fun text(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int,bold:Boolean,align:Paint.Align){
        p.shader=null; p.style=Paint.Style.FILL; p.color=color; p.textSize=size; p.textAlign=align
        p.typeface=if(bold) Typeface.create(Typeface.DEFAULT,Typeface.BOLD) else Typeface.DEFAULT
        c.drawText(s,x,y,p)
    }

    override fun onTouchEvent(e:MotionEvent):Boolean{
        val x=e.x; val y=e.y
        when(e.action){
            MotionEvent.ACTION_DOWN -> {
                lastTouchX=x; lastTouchY=y; dragging=false
                if(screen==Screen.GAME && !paused){
                    if(abs(x-(width-42f))<55f && abs(y-42f)<45f){
                        paused=true; invalidate(); return true
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if(screen==Screen.GAME && !paused){
                    dragging=true
                    val cut = ropes.firstOrNull {
                        distancePointToSegment(x,y,it.anchorX,it.anchorY,candy.x,candy.y) < 28f
                    }
                    if(cut != null){
                        ropes.remove(cut)
                        val dir = if(candy.x < nyam.x) 1f else -1f
                        candy.vx += 90f * dir
                        spawnBurst(candy.x,candy.y,Color.rgb(255,90,175),8)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handleTap(x,y)
                return true
            }
        }
        return true
    }

    private fun handleTap(x:Float,y:Float){
        val w=width.toFloat(); val h=height.toFloat()
        if(screen==Screen.GAME){
            if(paused){
                if(y in h*.40f..h*.52f){ paused=false; invalidate() }
                else if(y in h*.52f..h*.62f){ screen=Screen.MENU; paused=false; invalidate() }
                return
            }
            if(y > h-90f && x in 55f..250f){
                val cut = ropes.minByOrNull {
                    distancePointToSegment(x,y,it.anchorX,it.anchorY,candy.x,candy.y)
                }
                if(cut != null){
                    ropes.remove(cut)
                    val dir = if(candy.x < nyam.x) 1f else -1f
                    candy.vx += 150f * dir
                    candy.vy -= 35f
                    spawnBurst(candy.x,candy.y,Color.rgb(255,90,175),12)
                }
                invalidate()
                return
            }

            if(winTimer>0f){
                if(y in h*.49f..h*.59f){
                    currentLevel=(currentLevel+1).coerceAtMost(30)
                    startLevel(currentLevel)
                } else if(y in h*.59f..h*.70f){ screen=Screen.MENU; winTimer=0f }
                return
            }
            if(failTimer>0f){
                if(y in h*.47f..h*.57f){ startLevel(currentLevel) }
                else if(y in h*.57f..h*.68f){ screen=Screen.MENU; failTimer=0f }
                return
            }
            if(!dragging && abs(x-(w-42f))<55f && abs(y-42f)<45f){
                paused=true; invalidate()
            }
            return
        }

        when(screen){
            Screen.MENU -> when {
                y in h*.52f..h*.61f -> startLevel(currentLevel)
                y in h*.61f..h*.69f -> screen=Screen.LEVELS
                y in h*.69f..h*.78f -> screen=Screen.SHOP
                y in h*.78f..h*.87f -> screen=Screen.SETTINGS
            }
            Screen.LEVELS -> {
                if(y>h*.20f && y<h*.80f){
                    val row=((y-h*.25f)/(min(w*.25f,82f)*1.15f)).toInt().coerceIn(0,4)
                    val col=((x-w*.05f)/(w*.30f)).toInt().coerceIn(0,2)
                    val n=row*3+col+1
                    if(n<=bestLevel+1){ currentLevel=n; startLevel(n) }
                }
                if(y>h*.84f) screen=Screen.MENU
            }
            Screen.SHOP -> {
                if(y>h*.84f) screen=Screen.MENU
                if(y in h*.45f..h*.57f && x<w*.5f && coins>=300){
                    coins-=300; prefs.edit().putInt("coins",coins).apply()
                }
            }
            Screen.SETTINGS -> if(y>h*.84f) screen=Screen.MENU
            else -> {}
        }
        invalidate()
    }

    private fun startLevel(n:Int){
        currentLevel=n
        level=makeLevel(n)
        candy=Body(level.candyX,level.candyY,24f)
        nyam=Body(level.nyamX,level.nyamY,45f)
        ropes.clear(); bubbles.clear(); spikes.clear(); stars.clear(); particles.clear()
        ropes.addAll(level.ropes)
        bubbles.addAll(level.bubbles)
        spikes.addAll(level.spikes)
        stars.addAll(level.stars)
        starsCollected=0; paused=false; winTimer=0f; failTimer=0f
        screen=Screen.GAME
        invalidate()
    }

    override fun onDetachedFromWindow(){ super.onDetachedFromWindow() }

    private fun updatePhysics(dt:Float){
        if(screen!=Screen.GAME || paused || winTimer>0f || failTimer>0f) return

        candy.vy += 520f*dt
        candy.x += candy.vx*dt
        candy.y += candy.vy*dt

        // Верёвки ограничивают расстояние.
        for(r in ropes){
            val dx=candy.x-r.anchorX; val dy=candy.y-r.anchorY
            val d=hypot(dx,dy)
            if(d>r.length){
                val nx=dx/d; val nyy=dy/d
                candy.x=r.anchorX+nx*r.length
                candy.y=r.anchorY+nyy*r.length
                val vn=candy.vx*nx+candy.vy*nyy
                if(vn>0){ candy.vx-=vn*nx; candy.vy-=vn*nyy }
            }
        }

        // Пузырь подбрасывает конфету.
        for(b in bubbles){
            if(hypot(candy.x-b.x,candy.y-b.y)<candy.r+b.r*.72f){
                candy.vy=-420f
                spawnBurst(candy.x,candy.y,Color.rgb(120,220,255),10)
            }
        }

        // Звёзды.
        for(s in stars) if(!s.taken && hypot(candy.x-s.x,candy.y-s.y)<42f){
            s.taken=true; starsCollected++; coins+=5
            spawnBurst(s.x,s.y,Color.rgb(255,225,80),14)
        }

        // Шипы.
        for(s in spikes) if(hypot(candy.x-s.x,candy.y-(s.y-10f))<candy.r+s.size*.65f){
            failTimer=.01f; cameraShake=1f; spawnBurst(candy.x,candy.y,Color.rgb(255,80,110),18)
        }

        // Земля.
        val ground=height*.73f
        if(candy.y+candy.r>ground){
            candy.y=ground-candy.r
            candy.vy*=-.45f
            candy.vx*=.96f
        }

        if(candy.x < -100f || candy.x > width + 100f || candy.y > height + 120f){
            failTimer=.01f
            spawnBurst(candy.x.coerceIn(0f,width.toFloat()), height*.70f,
                Color.rgb(255,80,110), 14)
        }

        // Цель.
        if(hypot(candy.x-nyam.x,candy.y-nyam.y)<candy.r+nyam.r*.75f){
            winTimer=.01f
            coins+=25
            if(currentLevel>=bestLevel) bestLevel=currentLevel+1
            prefs.edit().putInt("coins",coins).putInt("best",bestLevel).apply()
            spawnBurst(nyam.x,nyam.y,Color.rgb(255,225,90),28)
        }

        candy.vx*=.992f
        particles.forEach {
            it.x+=it.vx*dt; it.y+=it.vy*dt; it.vy+=120f*dt; it.life-=dt*1.5f
        }
        particles.removeAll{it.life<=0}
        if(cameraShake>0) cameraShake-=dt*2f
        invalidate()
    }

    private fun spawnBurst(x:Float,y:Float,color:Int,count:Int){
        repeat(count.coerceAtMost(40)){
            val a=Random.nextFloat()*PI.toFloat()*2f
            val speed=80f+Random.nextFloat()*240f
            particles.add(Particle(x,y,cos(a)*speed,sin(a)*speed,2f+Random.nextFloat()*5f,color,1f))
        }
    }

    private fun makeLevel(n:Int):LevelData{
        val w=resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(360f)
        val h=resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(640f)
        val ground=h*.73f

        // Игровая траектория построена так, чтобы каждый уровень можно было пройти.
        // Конфета стартует сбоку от крепления и естественно раскачивается как маятник.
        val anchorX = w * if (n % 2 == 0) .42f else .38f
        val anchorY = h * .15f
        val ropeLength = min(w*.30f, 235f)
        val startX = anchorX + ropeLength * .72f
        val startY = anchorY + ropeLength * .55f

        val targetX = w * if (n % 3 == 0) .78f else .74f
        val targetY = ground - 48f

        val rs=mutableListOf<Rope>()
        rs.add(Rope(anchorX,anchorY,ropeLength))
        if(n>=5) rs.add(Rope(w*.63f,h*.18f,min(w*.25f,190f)))

        val bs=mutableListOf<Bubble>()
        bs.add(Bubble(w*.55f,ground-105f,34f))
        if(n>=6) bs.add(Bubble(w*.70f,ground-175f,30f))

        val ss=mutableListOf<Spike>()
        if(n>=3) ss.add(Spike(w*.60f,ground,24f))
        if(n>=7) ss.add(Spike(w*.68f,ground,24f))
        if(n>=12) ss.add(Spike(w*.46f,ground,24f))

        val st=mutableListOf<Star>()
        st.add(Star(w*.48f,h*.31f))
        st.add(Star(w*.62f,h*.43f))
        st.add(Star(targetX,h*.56f))

        return LevelData(startX,startY,targetX,targetY,rs,bs,ss,st)
    }

    private fun distancePointToSegment(px:Float,py:Float,ax:Float,ay:Float,bx:Float,by:Float):Float{
        val dx=bx-ax; val dy=by-ay
        if(dx==0f && dy==0f) return hypot(px-ax,py-ay)
        val t=((px-ax)*dx+(py-ay)*dy)/(dx*dx+dy*dy)
        val q=t.coerceIn(0f,1f)
        return hypot(px-(ax+q*dx),py-(ay+q*dy))
    }

    private data class Body(var x:Float,var y:Float,val r:Float,var vx:Float=0f,var vy:Float=0f)
    private data class Rope(val anchorX:Float,val anchorY:Float,val length:Float)
    private data class Bubble(val x:Float,val y:Float,val r:Float)
    private data class Spike(val x:Float,val y:Float,val size:Float)
    private data class Star(val x:Float,val y:Float,var taken:Boolean=false)
    private data class Particle(var x:Float,var y:Float,var vx:Float,var vy:Float,var size:Float,val color:Int,var life:Float)
    private data class LevelData(
        val candyX:Float,val candyY:Float,val nyamX:Float,val nyamY:Float,
        val ropes:List<Rope>,val bubbles:List<Bubble>,val spikes:List<Spike>,val stars:List<Star>
    )

    // Запускаем физику с частым кадром.
    override fun onWindowVisibilityChanged(visibility:Int){
        super.onWindowVisibilityChanged(visibility)
        if(visibility==VISIBLE){
            var last=System.nanoTime()
            fun tick(){
                if(!isAttachedToWindow) return
                val now=System.nanoTime()
                val dt=((now-last)/1_000_000_000.0).toFloat().coerceIn(0f,.033f)
                last=now
                updatePhysics(dt)
                if(winTimer>0f){ winTimer+=dt; if(winTimer>.20f) invalidate() }
                if(failTimer>0f){ failTimer+=dt; if(failTimer>.20f) invalidate() }
                postDelayed({ tick() },16)
            }
            tick()
        }
    }
}
