from PIL import Image
from collections import Counter
DARK=(0,0,0,230); LIT=(20,16,24,241)
STRIP_W=40                       # 斜めの "MOD" 文字は x=34 まで伸びる（実測）
def checker(x,y): return DARK if (x//4+y//4)%2==1 else LIT
def is_red(c):                   # 帯の赤い文字 (215,50,63) だけを拾う。主題は灰/茶なので落ちる
    r,g,b,a=c; return a>0 and r>150 and r>2*g and r>2*b

src=Image.open('_ref/fight_or_flight.png').convert('RGBA'); sp=src.load()   # 赤 "MOD"
tdm=Image.open('_ref/tdmon.png').convert('RGBA'); tp=tdm.load()             # 帯構造の型紙(主題が被っていない)
W,H=src.size

def cleared():
    im=Image.new('RGBA',(W,H),(0,0,0,0)); p=im.load()
    for y in range(H):
        for x in range(W):
            v=sp[x,y]
            if v[3]==0: continue
            if x<STRIP_W:
                # 帯構造(バー/バッジ/縁/文字)は tdmon 側も非チェック。それ以外は主題の被り
                p[x,y]= v if (tp[x,y][3]>0 and tp[x,y] not in (DARK,LIT)) else checker(x,y)
            else:
                p[x,y]= v if v in (DARK,LIT) else checker(x,y)
    return im

def bird(path,w):
    im=Image.open(path).convert('RGBA'); im=im.crop(im.getbbox())
    return im.resize((w,max(1,round(w*im.height/im.width))), Image.NEAREST)

c=cleared(); b=bird('_cobblemon_assets/pidgey_renders/pidgey_single_a.png',58)
for (x,y) in [(45,16),(30,58),(61,58)]:
    assert x+b.width<=119 and y+b.height<=116, (x,y,b.size)
    c.alpha_composite(b,(x,y))
c.save('icon-candidates/v14_pidgey_tri_red_mod_128.png')
for s in (512,256,96,48): c.resize((s,s),Image.NEAREST).save('icon-candidates/v14_pidgey_tri_red_mod_%d.png'%s)

p=c.load()
band=sum(1 for y in range(H) for x in range(STRIP_W)
         if tp[x,y][3]>0 and tp[x,y] not in (DARK,LIT) and p[x,y]!=sp[x,y])
red_keep=sum(1 for y in range(H) for x in range(STRIP_W) if is_red(sp[x,y]) and p[x,y]==sp[x,y])
red_lost=sum(1 for y in range(H) for x in range(STRIP_W) if is_red(sp[x,y]) and p[x,y]!=sp[x,y])
dirty=sum(1 for y in range(H) for x in range(STRIP_W)
          if p[x,y][3]>0 and p[x,y] not in (DARK,LIT) and not is_red(p[x,y])
          and not (tp[x,y][3]>0 and tp[x,y] not in (DARK,LIT)))
print('バー/バッジ/縁が実物と違う px =',band)
print('赤い MOD 文字 残 %d / 失 %d'%(red_keep,red_lost))
print('帯まわりに残った主題の被り px =',dirty)
print('bbox 出力=',c.getbbox(),' 元=',src.getbbox())
print('背景の主要色=',[k for k,v in Counter(p[x,y] for y in range(20,110) for x in range(95,118)).most_common(3)])
