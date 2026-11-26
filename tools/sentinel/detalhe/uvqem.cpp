// QEM com vertices de costura travados (UV preservado). uso: uvqem alvo_tris in.bin out.bin
// in.bin: i32 nv, i32 nt, f64 pos[nv*3], f64 uv[nv*2], f64 nrm[nv*3], u8 lock[nv], i32 idx[nt*3]
#include <bits/stdc++.h>
using namespace std;
typedef array<double,3> V3;
struct Q10{double q[10];};
static inline void qadd(Q10&a,const Q10&b){for(int i=0;i<10;i++)a.q[i]+=b.q[i];}
static inline double qev(const Q10&k,const V3&p){double x=p[0],y=p[1],z=p[2];
 return k.q[0]*x*x+2*k.q[1]*x*y+2*k.q[2]*x*z+2*k.q[3]*x+k.q[4]*y*y+2*k.q[5]*y*z+2*k.q[6]*y+k.q[7]*z*z+2*k.q[8]*z+k.q[9];}
int NV,NT; vector<V3> P,N; vector<array<double,2>> UV; vector<char> lk,vd,td; vector<array<int,3>> T; vector<vector<int>> vf; vector<Q10> Q; vector<int> ver;
struct E{float cost;int keep,rem;float t;int vk,vr;};
struct C{bool operator()(const E&a,const E&b)const{return a.cost>b.cost;}};
static V3 sub(V3 a,V3 b){return {a[0]-b[0],a[1]-b[1],a[2]-b[2]};}
static V3 cr(V3 a,V3 b){return {a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};}
static double dt(V3 a,V3 b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
static double uvarea(const array<double,2>&a,const array<double,2>&b,const array<double,2>&c){return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);}
// custo e posicao: t em [0,1] = fracao de keep->rem
E mk(int keep,int rem,float t){E e;e.keep=keep;e.rem=rem;e.t=t;e.vk=ver[keep];e.vr=ver[rem];
 V3 p={P[keep][0]+(P[rem][0]-P[keep][0])*t,P[keep][1]+(P[rem][1]-P[keep][1])*t,P[keep][2]+(P[rem][2]-P[keep][2])*t};
 Q10 s=Q[keep];qadd(s,Q[rem]);e.cost=(float)max(0.0,qev(s,p));
 // pequena penalidade pelo comprimento (evita triangulos longos)
 double L=sqrt(dt(sub(P[keep],P[rem]),sub(P[keep],P[rem])));e.cost+= (float)(L*L*1e-3*0);return e;}
bool ok(const E&e){
 // simula: keep passa a ter pos/uv da colapsa; triangulos de rem (sem keep) trocam rem->keep; triangulos de keep (sem rem) so mudam pos
 V3 np_={P[e.keep][0]+(P[e.rem][0]-P[e.keep][0])*e.t,P[e.keep][1]+(P[e.rem][1]-P[e.keep][1])*e.t,P[e.keep][2]+(P[e.rem][2]-P[e.keep][2])*e.t};
 array<double,2> nu={UV[e.keep][0]+(UV[e.rem][0]-UV[e.keep][0])*e.t,UV[e.keep][1]+(UV[e.rem][1]-UV[e.keep][1])*e.t};
 for(int side=0;side<2;side++){int v=side?e.rem:e.keep;
  for(int ti:vf[v]){ if(td[ti])continue; auto&t=T[ti]; bool hk=(t[0]==e.keep||t[1]==e.keep||t[2]==e.keep),hr=(t[0]==e.rem||t[1]==e.rem||t[2]==e.rem); if(hk&&hr)continue;
   V3 p[3];array<double,2> u[3];V3 po[3];
   for(int k=0;k<3;k++){int w=t[k];po[k]=P[w];if(w==e.keep||w==e.rem){p[k]=np_;u[k]=nu;}else{p[k]=P[w];u[k]=UV[w];}}
   V3 n0=cr(sub(po[1],po[0]),sub(po[2],po[0])),n1=cr(sub(p[1],p[0]),sub(p[2],p[0]));
   double l0=sqrt(dt(n0,n0)),l1=sqrt(dt(n1,n1)); if(l1<1e-14)return false; if(dt(n0,n1)/(l0*l1+1e-30)<0.35)return false;
   // UV: orientacao nao pode inverter
   array<double,2> uo[3];for(int k=0;k<3;k++)uo[k]=UV[t[k]];
   double a0=uvarea(uo[0],uo[1],uo[2]),a1=uvarea(u[0],u[1],u[2]); if(a0*a1<0)return false;
   // qualidade minima
   double e0=dt(sub(p[1],p[0]),sub(p[1],p[0])),e1=dt(sub(p[2],p[1]),sub(p[2],p[1])),e2=dt(sub(p[0],p[2]),sub(p[0],p[2]));
   double q=2*sqrt(3.0)*l1/2/(e0+e1+e2+1e-30)*2; if(q<0.04)return false;
  }}
 return true;}
int main(int argc,char**argv){int target=atoi(argv[1]);FILE*f=fopen(argv[2],"rb");fread(&NV,4,1,f);fread(&NT,4,1,f);
 P.resize(NV);UV.resize(NV);N.resize(NV);lk.resize(NV);T.resize(NT);
 vector<double> b(NV*3);fread(b.data(),8,NV*3,f);for(int i=0;i<NV;i++)P[i]={b[i*3],b[i*3+1],b[i*3+2]};
 b.resize(NV*2);fread(b.data(),8,NV*2,f);for(int i=0;i<NV;i++)UV[i]={b[i*2],b[i*2+1]};
 b.resize(NV*3);fread(b.data(),8,NV*3,f);for(int i=0;i<NV;i++)N[i]={b[i*3],b[i*3+1],b[i*3+2]};
 fread(lk.data(),1,NV,f);vector<int> ib(NT*3);fread(ib.data(),4,NT*3,f);fclose(f);for(int i=0;i<NT;i++)T[i]={ib[i*3],ib[i*3+1],ib[i*3+2]};
 vd.assign(NV,0);td.assign(NT,0);vf.assign(NV,{});ver.assign(NV,0);Q.assign(NV,Q10{});
 for(int i=0;i<NT;i++)for(int k=0;k<3;k++)vf[T[i][k]].push_back(i);
 for(int i=0;i<NT;i++){V3 n=cr(sub(P[T[i][1]],P[T[i][0]]),sub(P[T[i][2]],P[T[i][0]]));double l=sqrt(dt(n,n));if(l<1e-14)continue;double ar=l/2;n={n[0]/l,n[1]/l,n[2]/l};double d=-dt(n,P[T[i][0]]);
  Q10 k;double a=n[0],bb=n[1],c=n[2];k.q[0]=a*a*ar;k.q[1]=a*bb*ar;k.q[2]=a*c*ar;k.q[3]=a*d*ar;k.q[4]=bb*bb*ar;k.q[5]=bb*c*ar;k.q[6]=bb*d*ar;k.q[7]=c*c*ar;k.q[8]=c*d*ar;k.q[9]=d*d*ar;
  for(int j=0;j<3;j++)qadd(Q[T[i][j]],k);}
 int alive=NT;priority_queue<E,vector<E>,C> pq;
 auto push=[&](int a,int b){ if(lk[a]&&lk[b])return; vector<E> c; if(!lk[a]&&!lk[b]){c.push_back(mk(b,a,0));c.push_back(mk(a,b,0));c.push_back(mk(b,a,.5f));}
  else if(lk[a])c.push_back(mk(a,b,0)); else c.push_back(mk(b,a,0)); for(auto&e:c)pq.push(e);};
 { set<pair<int,int>> es;for(int i=0;i<NT;i++)for(int k=0;k<3;k++){int a=T[i][k],bq=T[i][(k+1)%3];if(a>bq)swap(a,bq);es.insert({a,bq});}
  for(auto&e:es)push(e.first,e.second);}
 fprintf(stderr,"edges pushed, tris %d\n",alive);
 while(alive>target&&!pq.empty()){E e=pq.top();pq.pop();
  if(vd[e.keep]||vd[e.rem]||ver[e.keep]!=e.vk||ver[e.rem]!=e.vr)continue;
  if(!ok(e))continue;
  // aplica
  V3 np_={P[e.keep][0]+(P[e.rem][0]-P[e.keep][0])*e.t,P[e.keep][1]+(P[e.rem][1]-P[e.keep][1])*e.t,P[e.keep][2]+(P[e.rem][2]-P[e.keep][2])*e.t};
  array<double,2> nu={UV[e.keep][0]+(UV[e.rem][0]-UV[e.keep][0])*e.t,UV[e.keep][1]+(UV[e.rem][1]-UV[e.keep][1])*e.t};
  V3 nn={N[e.keep][0]+(N[e.rem][0]-N[e.keep][0])*e.t,N[e.keep][1]+(N[e.rem][1]-N[e.keep][1])*e.t,N[e.keep][2]+(N[e.rem][2]-N[e.keep][2])*e.t};
  double l=sqrt(dt(nn,nn));if(l>1e-12)nn={nn[0]/l,nn[1]/l,nn[2]/l};
  vector<int> nf;
  for(int ti:vf[e.rem]){ if(td[ti])continue; auto&t=T[ti]; bool hk=(t[0]==e.keep||t[1]==e.keep||t[2]==e.keep); if(hk){td[ti]=1;alive--;continue;} for(int k=0;k<3;k++)if(t[k]==e.rem)t[k]=e.keep; vf[e.keep].push_back(ti);}
  P[e.keep]=np_;UV[e.keep]=nu;N[e.keep]=nn;qadd(Q[e.keep],Q[e.rem]);vd[e.rem]=1;ver[e.keep]++;ver[e.rem]++;
  // limpa lista e re-empurra arestas do keep
  vector<int> nl;for(int ti:vf[e.keep])if(!td[ti])nl.push_back(ti);sort(nl.begin(),nl.end());nl.erase(unique(nl.begin(),nl.end()),nl.end());vf[e.keep]=nl;
  set<int> nb;for(int ti:nl)for(int k=0;k<3;k++)if(T[ti][k]!=e.keep)nb.insert(T[ti][k]);
  for(int w:nb)push(e.keep,w);
  if(alive%20000==0)fprintf(stderr,"tris %d\n",alive);
 }
 fprintf(stderr,"final tris %d\n",alive);
 vector<int> mp(NV,-1);int nvo=0;vector<int> out;for(int i=0;i<NT;i++)if(!td[i])for(int k=0;k<3;k++){int v=T[i][k];if(mp[v]<0)mp[v]=nvo++;}
 FILE*o=fopen(argv[3],"wb");fwrite(&nvo,4,1,o);int nt=alive;fwrite(&nt,4,1,o);
 vector<double> bo;bo.reserve(nvo*8);vector<int> src(nvo);for(int i=0;i<NV;i++)if(mp[i]>=0)src[mp[i]]=i;
 for(int i=0;i<nvo;i++){int s=src[i];bo.insert(bo.end(),{P[s][0],P[s][1],P[s][2],UV[s][0],UV[s][1],N[s][0],N[s][1],N[s][2]});}
 fwrite(bo.data(),8,bo.size(),o);vector<int> io;for(int i=0;i<NT;i++)if(!td[i])for(int k=0;k<3;k++)io.push_back(mp[T[i][k]]);fwrite(io.data(),4,io.size(),o);
 fwrite(src.data(),4,nvo,o);fclose(o);}
