import React, { useState } from "react";
import {
  Activity, ArrowLeft, ArrowRight, ArrowUpRight, AudioLines, Check, ChevronRight,
  Clock3, Disc3, Gauge, Headphones, Home, Library, MapPin, Music2, Navigation,
  Pause, Play, Plus, Route, Settings2, ShieldCheck, SkipBack, SkipForward,
  SlidersHorizontal, Sparkles, Volume2, Waves, Wind, Zap
} from "lucide-react";

const moods = [
  { id: "lofi", name: "LO-FI", desc: "Warm · Laid-back", bpm: "82–96 BPM", color: "blue" },
  { id: "city", name: "CITY POP", desc: "Neon · Nostalgic", bpm: "96–112 BPM", color: "yellow" },
  { id: "ambient", name: "AMBIENT", desc: "Airy · Cinematic", bpm: "68–84 BPM", color: "ice" }
];
const tracks = [
  { title: "Midnight Avenue", date: "오늘 · 18:42", distance: "18.6 km", duration: "32분", mood: "CITY POP", score: 96, seed: 1 },
  { title: "Soft Current", date: "어제 · 08:16", distance: "12.4 km", duration: "24분", mood: "AMBIENT", score: 92, seed: 2 },
  { title: "Blue Hour Loop", date: "10.30 · 19:05", distance: "21.2 km", duration: "38분", mood: "LO-FI", score: 89, seed: 3 },
  { title: "Golden Line", date: "10.29 · 17:51", distance: "9.8 km", duration: "19분", mood: "CITY POP", score: 94, seed: 4 }
];

function Brand() {
  return <div className="brand"><span className="brand-mark"><i/><i/><i/></span><span>SYNE<span className="brand-dot">.</span></span></div>;
}
function Art({ variant = "hero", seed = 1 }) {
  return <div className={`art art-${variant} seed-${seed}`} aria-label="생성형 사운드 아트">
    <div className="art-grid"/><div className="art-orbit orbit-one"/><div className="art-orbit orbit-two"/>
    <div className="art-core"/><div className="art-wave wave-one"/><div className="art-wave wave-two"/><div className="art-wave wave-three"/>
    <span className="art-coordinate">37° 33' 12.4"N<br/>126° 58' 42.1"E</span>
    <span className="art-index">SOUND / {String(seed).padStart(2, "0")}</span>
  </div>;
}
function SectionTitle({ eyebrow, title, action }) {
  return <div className="section-title"><div><div className="eyebrow">{eyebrow}</div><h2>{title}</h2></div>{action}</div>;
}
function Nav({ active, setActive }) {
  const items = [{ id: "home", label: "홈", icon: Home }, { id: "play", label: "플레이", icon: AudioLines }, { id: "archive", label: "아카이브", icon: Library }, { id: "report", label: "리포트", icon: Activity }];
  return <nav className="bottom-nav">{items.map(({ id, label, icon: Icon }) =>
    <button key={id} className={`nav-item ${active === id ? "active" : ""}`} onClick={() => setActive(id)}>
      <Icon size={19} strokeWidth={1.8}/><span>{label}</span>
    </button>)}</nav>;
}
function HomeScreen({ mood, setMood, destination, setDestination, startDrive, setActive }) {
  const [duration, setDuration] = useState("약 32분");
  return <div className="screen home-screen">
    <header className="topbar"><Brand/><button className="icon-btn" aria-label="설정"><Settings2 size={19}/></button></header>
    <div className="welcome"><div className="eyebrow">MONDAY, NOVEMBER 02</div><h1>오늘의 움직임을<br/><em>음악으로</em> 만들어보세요.</h1><p>당신의 주행은 매번 다른 사운드가 됩니다.</p></div>
    <div className="hero-art-wrap"><Art variant="hero" seed={3}/><div className="art-caption"><span className="live-dot"/> GENERATIVE SOUND ENGINE <span>V 1.0</span></div></div>
    <section className="section-block">
      <SectionTitle eyebrow="01 / SELECT YOUR MOOD" title="오늘의 무드"/>
      <div className="mood-grid">{moods.map(m => <button key={m.id} onClick={() => setMood(m.id)} className={`mood-card ${mood === m.id ? "selected" : ""} mood-${m.color}`}>
        <span className="mood-orb"/><span className="mood-name">{m.name}</span><span className="mood-desc">{m.desc}</span>{mood === m.id && <Check className="mood-check" size={15}/>}
      </button>)}</div>
      <div className="mood-meta"><span>GENERATIVE RANGE</span><strong>{moods.find(m => m.id === mood).bpm}</strong></div>
    </section>
    <section className="section-block route-panel">
      <SectionTitle eyebrow="02 / YOUR DESTINATION" title="어디로 갈까요?"/>
      <label className="field-label" htmlFor="destination">목적지</label>
      <div className="destination-field"><MapPin size={17}/><input id="destination" value={destination} onChange={e => setDestination(e.target.value)} placeholder="목적지를 입력하세요"/><button className="locate-btn" aria-label="현재 위치"><Navigation size={16}/></button></div>
      <div className="route-options"><div><span className="field-label">예상 주행 시간</span><div className="duration-select"><Clock3 size={15}/><select value={duration} onChange={e => setDuration(e.target.value)}><option>약 32분</option><option>약 45분</option><option>약 60분</option></select></div></div><div className="route-note"><Route size={15}/><span>도착에 맞춰 피날레가 완성돼요</span></div></div>
    </section>
    <button className="primary-btn create-btn" onClick={startDrive}><Sparkles size={17}/> CREATE MY SOUND <ArrowUpRight size={17}/></button>
    <p className="safety-note"><ShieldCheck size={13}/> 주행 중 조작 없이 오디오로만 경험합니다.</p>
    <button className="text-link" onClick={() => setActive("archive")}>지난 주행곡 둘러보기 <ChevronRight size={14}/></button>
  </div>;
}
function PlayScreen({ setActive, mood, destination }) {
  const [playing, setPlaying] = useState(true);
  const [sim, setSim] = useState("smooth");
  const [elapsed, setElapsed] = useState(13);
  const pct = Math.min(100, Math.round(elapsed / 32 * 100));
  return <div className="screen play-screen">
    <header className="topbar"><button className="back-btn" onClick={() => setActive("home")}><ArrowLeft size={18}/> 출발 화면</button><span className="play-status"><i/> LIVE SESSION</span></header>
    <div className="play-heading"><div className="eyebrow">YOUR DRIVE, YOUR COMPOSITION</div><h1>움직임이<br/><em>연주되고 있어요.</em></h1></div>
    <Art variant="player" seed={mood === "city" ? 2 : mood === "ambient" ? 4 : 1}/>
    <div className="track-now"><div><div className="eyebrow">NOW COMPOSING</div><h2>{moods.find(m => m.id === mood).name} / No. 024</h2><p>{destination || "목적지 미설정"} · 오늘의 주행곡</p></div><div className="sound-icon"><AudioLines size={22}/></div></div>
    <div className="progress-wrap"><div className="progress-label"><span>13:00</span><span>32:00</span></div><div className="progress"><i style={{width: `${pct}%`}}/></div><input aria-label="재생 진행" type="range" min="0" max="32" value={elapsed} onChange={e => setElapsed(Number(e.target.value))}/></div>
    <div className="player-controls"><button aria-label="이전"><SkipBack size={19}/></button><button className="play-toggle" onClick={() => setPlaying(!playing)} aria-label={playing ? "일시정지" : "재생"}>{playing ? <Pause size={22} fill="currentColor"/> : <Play size={22} fill="currentColor"/>}</button><button aria-label="다음"><SkipForward size={19}/></button></div>
    <section className="live-data">
      <SectionTitle eyebrow="LIVE VEHICLE INPUT" title="주행이 사운드가 되는 중"/>
      <div className="telemetry-grid"><div><Gauge/><span>속도</span><strong>48 <small>km/h</small></strong></div><div><Wind/><span>주행 감도</span><strong>부드러움</strong></div><div><Waves/><span>하모니</span><strong>풍성하게</strong></div></div>
      <div className="safe-banner"><ShieldCheck size={16}/><span>안전을 우선합니다. 주행 중 화면 조작은 제한됩니다.</span></div>
    </section>
    <div className="demo-panel"><div className="demo-head"><span><Zap size={14}/> DEMO CONSOLE</span><span>시연용 입력</span></div><p>실제 차량 연결 전, 이벤트를 시뮬레이션합니다.</p><div className="demo-actions"><button className={sim === "smooth" ? "chosen" : ""} onClick={() => setSim("smooth")}>부드러운 주행</button><button className={sim === "brake" ? "chosen danger" : ""} onClick={() => setSim("brake")}>급제동 이벤트</button></div><div className={`demo-feedback ${sim === "brake" ? "broken" : ""}`}>{sim === "brake" ? "음악 레이어가 일시적으로 흐트러집니다." : "하모니가 안정적으로 쌓이고 있습니다."}</div></div>
  </div>;
}
function ArchiveScreen({ setActive }) {
  const [filter, setFilter] = useState("ALL");
  const visible = tracks.filter(t => filter === "ALL" || t.mood === filter);
  return <div className="screen archive-screen">
    <header className="topbar"><Brand/><button className="icon-btn"><SlidersHorizontal size={18}/></button></header>
    <div className="page-heading"><div className="eyebrow">YOUR PERSONAL SOUND COLLECTION</div><h1>나의 주행은<br/><em>하나의 앨범이 됩니다.</em></h1><p>지나온 길을 음악으로 다시 만나보세요.</p></div>
    <div className="archive-feature"><Art variant="feature" seed={1}/><div className="feature-copy"><span className="eyebrow">OCTOBER COLLECTION</span><h2>Routes<br/>in Motion</h2><p>12 TRACKS · 4H 32M</p><button onClick={() => setActive("report")}>컬렉션 보기 <ArrowUpRight size={14}/></button></div></div>
    <div className="filter-row">{["ALL", "CITY POP", "AMBIENT", "LO-FI"].map(f => <button key={f} className={filter === f ? "filter active" : "filter"} onClick={() => setFilter(f)}>{f}</button>)}</div>
    <SectionTitle eyebrow="RECENTLY CREATED" title="최근 주행곡" action={<span className="count-label">{visible.length} TRACKS</span>}/>
    <div className="track-list">{visible.map(t => <div className="track-row" key={t.title}><Art variant="thumb" seed={t.seed}/><div className="track-info"><strong>{t.title}</strong><span>{t.date} · {t.mood}</span></div><div className="track-score"><strong>{t.score}</strong><span>%</span></div><button className="row-arrow" aria-label={`${t.title} 상세`}><ChevronRight size={17}/></button></div>)}</div>
    <button className="outline-btn" onClick={() => setActive("report")}><Disc3 size={16}/> 나의 주행 리포트 보기</button>
  </div>;
}
function ReportScreen({ setActive }) {
  return <div className="screen report-screen">
    <header className="topbar"><button className="back-btn" onClick={() => setActive("archive")}><ArrowLeft size={18}/> 아카이브</button><span className="eyebrow">DRIVE SUMMARY / 024</span></header>
    <div className="page-heading"><div className="eyebrow">YOUR DRIVE, TRANSFORMED</div><h1>오늘의 주행이<br/><em>작품이 되었습니다.</em></h1><p>11월 02일 · 18:10 — 18:42</p></div>
    <div className="report-art"><Art variant="report" seed={1}/><div className="report-art-label"><span>SYNE ORIGINAL</span><span>NO. 024 / 2026</span></div></div>
    <div className="score-card"><div><span className="eyebrow">COMPOSITION COMPLETION</span><div className="score-value">92<span>%</span></div><p>부드러운 흐름이 아름다운 하모니를 만들었어요.</p></div><div className="score-ring"><svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="42"/><circle className="ring-value" cx="50" cy="50" r="42"/></svg><span>92</span></div></div>
    <SectionTitle eyebrow="DRIVE INSIGHTS" title="오늘의 움직임"/>
    <div className="stats-grid"><div className="stat"><Route/><span>주행 거리</span><strong>18.6 <small>km</small></strong></div><div className="stat"><Clock3/><span>주행 시간</span><strong>32 <small>min</small></strong></div><div className="stat"><ShieldCheck/><span>부드러운 주행</span><strong>94 <small>%</small></strong></div><div className="stat"><Activity/><span>급제동</span><strong>1 <small>회</small></strong></div></div>
    <div className="highlight-card"><div className="highlight-icon"><AudioLines/></div><div><span className="eyebrow">30 SEC HIGHLIGHT</span><strong>Midnight Avenue</strong><p>오늘의 가장 부드러운 구간을 담았어요.</p></div><button className="round-play" aria-label="하이라이트 재생"><Play size={17} fill="currentColor"/></button></div>
    <button className="primary-btn" onClick={() => setActive("archive")}><Library size={17}/> 아카이브에 저장 완료 <ArrowRight size={17}/></button>
    <p className="safety-note"><ShieldCheck size={13}/> 완성도는 안전운전 피드백을 위한 참고 지표입니다.</p>
  </div>;
}
export default function App() {
  const [active, setActive] = useState("home");
  const [mood, setMood] = useState("city");
  const [destination, setDestination] = useState("성수동, 서울");
  const startDrive = () => setActive("play");
  return <div className="app-shell"><div className="ambient ambient-one"/><div className="ambient ambient-two"/>
    <main className="app-frame">
      {active === "home" && <HomeScreen {...{mood, setMood, destination, setDestination, startDrive, setActive}}/>}
      {active === "play" && <PlayScreen {...{setActive, mood, destination}}/>}
      {active === "archive" && <ArchiveScreen setActive={setActive}/>}
      {active === "report" && <ReportScreen setActive={setActive}/>}
      <Nav active={active} setActive={setActive}/>
    </main>
  </div>;
}