from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.svgLib.path import parse_path
from xml.sax.saxutils import escape
import subprocess, json, math

ROOT=Path(__file__).resolve().parent
WHITE='#F5F7FA'; BLUE='#77B8FF'; DARK='#101216'; SECOND='#B8C0CC'
font=instantiateVariableFont(TTFont(ROOT/'fonts/AtkinsonHyperlegibleNext.ttf'),{'wght':600})
glyphs=font.getGlyphSet(); cmap=font.getBestCmap()

def transform(d, matrix):
 p=SVGPathPen(None, ntos=lambda n: format(n,'.3f').rstrip('0').rstrip('.') if n else '0')
 parse_path(d,TransformPen(p,matrix)); return p.getCommands()
def glyph(c,x,baseline=740):
 p=SVGPathPen(glyphs); glyphs[cmap[ord(c)]].draw(TransformPen(p,(1,0,0,-1,x,baseline)))
 return p.getCommands()
def bbox(parts):
 p=BoundsPen(None)
 for _,d in parts: parse_path(d,p)
 return p.bounds

def fit(parts,x,y,w,h):
 x0,y0,x1,y1=bbox(parts);s=min(w/(x1-x0),h/(y1-y0))
 tx=x+(w-s*(x1-x0))/2-s*x0;ty=y+(h-s*(y1-y0))/2-s*y0
 return [(c,transform(d,(s,0,0,s,tx,ty))) for c,d in parts]

def recolor(parts,light=False,mono=False):
 return [(('#FFFFFF' if mono else ('#1F5FAD' if c==BLUE else DARK) if light else c),d) for c,d in parts]

def svg(parts,w,h,bg=None,ambient=False):
 defs='';back=''
 if ambient:
  defs='<defs><radialGradient id="ambient" cx="72%" cy="18%" r="90%"><stop offset="0" stop-color="#1C2027"/><stop offset="1" stop-color="#101216"/></radialGradient></defs>'
  back=f'<path fill="url(#ambient)" d="M0 0H{w}V{h}H0Z"/>'
 elif bg:back=f'<path fill="{bg}" d="M0 0H{w}V{h}H0Z"/>'
 return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" role="img" aria-label="Tiviman">{defs}{back}'+''.join(f'<path fill="{c}" d="{d}"/>' for c,d in parts)+'</svg>\n'

def write(rel,data):
 p=ROOT/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(data);return p

def render(src,dst,width=None,height=None):
 args=['node',str(ROOT/'render_svg.cjs'),str(ROOT/src),str(ROOT/dst),str(width) if width else '',str(height) if height else '']
 (ROOT/dst).parent.mkdir(parents=True,exist_ok=True)
 subprocess.run(args,check=True,stdout=subprocess.DEVNULL)

def vector(parts,w,h,background=False,ambient=False):
 attrs='xmlns:android="http://schemas.android.com/apk/res/android"'
 if ambient:attrs+=' xmlns:aapt="http://schemas.android.com/aapt"'
 data=f'<?xml version="1.0" encoding="utf-8"?>\n<vector {attrs} android:width="{w}dp" android:height="{h}dp" android:viewportWidth="{w}" android:viewportHeight="{h}">\n'
 if ambient:
  data+=f'<path android:pathData="M0 0H{w}V{h}H0Z"><aapt:attr name="android:fillColor"><gradient android:type="radial" android:centerX="{w*.72}" android:centerY="{h*.18}" android:gradientRadius="{max(w,h)*.9}" android:startColor="#1C2027" android:endColor="#101216"/></aapt:attr></path>\n'
 elif background:data+=f'<path android:fillColor="{DARK}" android:pathData="M0 0H{w}V{h}H0Z"/>\n'
 data+=''.join(f'<path android:fillColor="{c}" android:pathData="{d}"/>\n' for c,d in parts)
 return data+'</vector>\n'

cape='M275 365C245 370 178 485 130 542C95 600 55 630 20 650C110 700 190 715 275 710Z'
mark=[(BLUE,cape),(WHITE,glyph('t',200))]
word=[(BLUE,cape)]
x=200
for index,c in enumerate('tiviman'):
 d=glyph(c,x)
 if c=='i':
  contours=['M'+s for s in d.split('M') if s.strip()]
  for contour in contours:
   b=bbox([(WHITE,contour)])
   word.append((BLUE if b[3]<200 else WHITE,contour))
 else:word.append((WHITE,d))
 x+=font['hmtx'][cmap[ord(c)]][0]-10
 if index in [1,2]:x-=5

for name,parts in [('tiviman-mark',mark),('tiviman-wordmark',word)]:
 b=bbox(parts); w=round(b[2]-b[0]+120);h=round(b[3]-b[1]+120)
 normalized=fit(parts,60,60,w-120,h-120)
 for suffix,bg,light,mono in [('',None,False,False),('-dark',DARK,False,False),('-light','#FFFFFF',True,False),('-monochrome',None,False,True)]:
  write('source/'+name+suffix+'.svg',svg(recolor(normalized,light,mono),w,h,bg))
 render('source/'+name+'.svg','previews/'+name+'-transparent.png',width=2400 if parts is word else 512)
 render('source/'+name+'-dark.svg','previews/'+name+'-dark.png',width=2400 if parts is word else 512)

banner=fit(word,32,50,256,80)
write('source/tv-banner.svg',svg(banner,320,180,ambient=True))
render('source/tv-banner.svg','previews/tv-banner-320x180.png',320,180)
render('source/tv-banner.svg','previews/tv-banner-1280x720.png',1280,720)
res='../../app/src/main/res/'
write(res+'drawable/tv_banner.xml',vector(banner,320,180,background=True))
write(res+'drawable-v24/tv_banner.xml',vector(banner,320,180,ambient=True))

# A 48dp-tall silhouette sits wholly inside the central 66dp safe circle.
fg=fit(mark,30,30,48,48)
write('source/adaptive-foreground.svg',svg(fg,108,108))
write('source/adaptive-background.svg',svg([],108,108,DARK))
write('source/adaptive-monochrome.svg',svg(recolor(fg,mono=True),108,108))
write(res+'drawable/ic_launcher_foreground.xml',vector(fg,108,108))
write(res+'drawable/ic_launcher_monochrome.xml',vector(recolor(fg,mono=True),108,108))
write(res+'values/tiviman_colors.xml','<resources><color name="tiviman_canvas">#101216</color></resources>\n')
write(res+'values/tiviman_strings.xml','<resources><string name="tiviman_app_name">Tiviman</string></resources>\n')
for level,mono in [('v26',False),('v33',True)]:
 a='<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n<background android:drawable="@color/tiviman_canvas"/>\n<foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
 if mono:a+='<monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n'
 a+='</adaptive-icon>\n'
 for name in ['ic_launcher','ic_launcher_round']:write(res+f'mipmap-anydpi-{level}/{name}.xml',a)

# Legacy launcher files for pre-adaptive launchers; PNGs avoid vector launcher compatibility issues.
legacy=fit(mark,24,24,60,60)
write('source/launcher-legacy.svg',svg(legacy,108,108,DARK))
render('source/launcher-legacy.svg','previews/launcher-512.png',512,512)
from PIL import Image,ImageDraw
for density,size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
 out=f'{res}mipmap-{density}/ic_launcher.png';render('source/launcher-legacy.svg',out,size,size)
 im=Image.open(ROOT/out).convert('RGBA');mask=Image.new('L',im.size);ImageDraw.Draw(mask).ellipse((0,0,size-1,size-1),fill=255);im.putalpha(mask)
 im.save(ROOT/res/f'mipmap-{density}/ic_launcher_round.png')

# Outline the descriptor as well; no external font dependency in SVG exports.
reg=instantiateVariableFont(TTFont(ROOT/'fonts/AtkinsonHyperlegibleNext.ttf'),{'wght':400}); rg=reg.getGlyphSet()
def text_paths(text,size,x,baseline,color):
 result=[];scale=size/1000
 for c in text:
  n=cmap[ord(c)];p=SVGPathPen(rg);rg[n].draw(TransformPen(p,(scale,0,0,-scale,x,baseline)))
  if p.getCommands():result.append((color,p.getCommands()))
  x+=reg['hmtx'][n][0]*scale
 return result
social=fit(word,180,180,920,220)
line1='Open-source IPTV player for Android TV,'
line2='built around the programme guide.'
for line,base in [(line1,472),(line2,512)]:
 p=text_paths(line,29,0,base,SECOND);b=bbox(p);social +=[(c,transform(d,(1,0,0,1,640-(b[0]+b[2])/2,0))) for c,d in p]
write('source/repository-social-preview.svg',svg(social,1280,640,DARK))
render('source/repository-social-preview.svg','previews/repository-social-preview-1280x640.png',1280,640)

# Preview sheet uses the very same vectors as the deliverables.
board=fit(word,110,70,980,230)
board+=fit(mark,110,400,200,220)
board+=fit(recolor(mark,mono=True),430,400,200,220)
board+=fit(word,755,430,350,110)
for label,xx,yy in [('Final wordmark',110,45),('Launcher symbol',110,680),('Monochrome symbol',430,680),('TV banner composition',755,680)]:board+=text_paths(label,23,xx,yy,SECOND)
write('source/asset-overview.svg',svg(board,1200,740,DARK))
render('source/asset-overview.svg','previews/asset-overview.png',1600)

# Static structural checks, resource resolution, export dimensions and safe area.
import xml.etree.ElementTree as ET
for p in ROOT.rglob('*.xml'):ET.parse(p)
for p in (ROOT/'source').glob('*.svg'):ET.parse(p)
for f,size in [('tv-banner-320x180.png',(320,180)),('repository-social-preview-1280x640.png',(1280,640)),('launcher-512.png',(512,512))]:assert Image.open(ROOT/'previews'/f).size==size
b=bbox(fg)
assert all(math.hypot(px-54,py-54)<=33 for px in [b[0],b[2]] for py in [b[1],b[3]])
assert sum(c==BLUE for c,d in word)==3,'Cape plus exactly two i dots expected'
print(json.dumps({'font':'Atkinson Hyperlegible Next, weight 600','safe_bounds':b,'xml_files':len(list(ROOT.rglob('*.xml'))),'status':'passed'}))
