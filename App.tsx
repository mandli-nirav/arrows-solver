import React, {useCallback, useEffect, useState} from 'react';
import {AppState, Pressable, ScrollView, StatusBar, StyleSheet, Text, View} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import NativeSolver from './specs/NativeSolver';

/**
 * Same look as the Arrows game itself (taken from its Settings screen, v0.24.0):
 * background #1E1F2D, cards #32374E, selected #404668, text #AEB6E1, accent #5B71FF.
 * The game uses the commercial Gordita font; Poppins (OFL, bundled) is the closest free match.
 */
const C = {
  bg: '#1E1F2D',
  card: '#32374E',
  raised: '#404668',
  text: '#AEB6E1',
  dim: '#7F87B3',
  accent: '#5B71FF',
  trackOff: '#1D1F2D',
  red: '#FF4B5D',
};
const F = {medium: 'Poppins-Medium', semi: 'Poppins-SemiBold'};

function Toggle({on}: {on: boolean}) {
  return (
    <View style={[s.track, {backgroundColor: on ? C.accent : C.trackOff}]}>
      <View style={[s.knob, on ? s.knobOn : s.knobOff]} />
    </View>
  );
}

function Row({
  label,
  hint,
  right,
  onPress,
}: {
  label: string;
  hint?: string;
  right?: React.ReactNode;
  onPress?: () => void;
}) {
  return (
    <Pressable
      accessibilityRole={onPress ? 'button' : 'text'}
      accessibilityLabel={hint ? `${label}. ${hint}` : label}
      onPress={onPress}
      disabled={!onPress}
      android_ripple={onPress ? {color: C.raised} : undefined}
      style={s.row}>
      <View style={s.rowText}>
        <Text style={s.rowLabel}>{label}</Text>
        {hint ? <Text style={s.rowHint}>{hint}</Text> : null}
      </View>
      {right}
    </Pressable>
  );
}

const Chevron = () => <Text style={s.chevron}>›</Text>;

function Card({children}: {children: React.ReactNode}) {
  return <View style={s.card}>{children}</View>;
}

function Step({n, children}: {n: number; children: string}) {
  return (
    <View style={s.step}>
      <Text style={s.stepNum}>{n}</Text>
      <Text style={s.stepText}>{children}</Text>
    </View>
  );
}

function Stat({value, label}: {value: string; label: string}) {
  return (
    <View style={s.stat} accessible accessibilityLabel={`${label}: ${value}`}>
      <Text style={s.statValue}>{value}</Text>
      <Text style={s.statLabel}>{label}</Text>
    </View>
  );
}

export default function App() {
  const [enabled, setEnabled] = useState(false);
  const [status, setStatus] = useState('');
  const [gameMissing, setGameMissing] = useState(false);
  const [stats, setStats] = useState({levels: 0, arrows: 0, ms: 0});

  const refresh = useCallback(() => {
    setEnabled(NativeSolver.isServiceEnabled());
    setStatus(NativeSolver.getStatus());
    setStats(NativeSolver.getStats());
  }, []);

  useEffect(() => {
    refresh();
    const sub = AppState.addEventListener('change', st => st === 'active' && refresh());
    const t = setInterval(refresh, 1500);
    return () => {
      sub.remove();
      clearInterval(t);
    };
  }, [refresh]);

  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" />
      <SafeAreaView style={s.root}>
        <ScrollView contentContainerStyle={s.content}>
          <Text style={s.title}>
            <Text style={s.titleArrow}>▲</Text>rrows Solver
          </Text>
          <Text style={s.sub}>A helper bubble over the Arrows game. It reads the level and clears it one arrow at a time.</Text>

          <Card>
            <Row
              label="Bubble service"
              hint={enabled ? 'On' : 'Tap to turn on in Accessibility'}
              right={<Toggle on={enabled} />}
              onPress={() => NativeSolver.openAccessibilitySettings()}
            />
            <Row
              label="Allow restricted settings"
              hint="Only if the service switch is greyed out"
              right={<Chevron />}
              onPress={() => NativeSolver.openAppInfo()}
            />
          </Card>

          <Card>
            <Row label="Open Arrows" right={<Chevron />} onPress={() => setGameMissing(!NativeSolver.openGame())} />
            {gameMissing ? <Text style={s.warn}>Arrows (com.ecffri.arrows) is not installed.</Text> : null}
          </Card>

          <Card>
            <Text style={s.cardTitle}>How to use</Text>
            <Step n={1}>Start a level and tap the blue bubble.</Step>
            <Step n={2}>Analyze. Green arrows can leave now, orange ones are blocked.</Step>
            <Step n={3}>Solve. One arrow at a time; it stops if anything looks wrong.</Step>
            <Step n={4}>Zoom out and Map show more of big boards. Keep hands off the screen while it runs.</Step>
          </Card>

          <Card>
            <Text style={s.cardTitle}>Solved so far</Text>
            <View style={s.stats}>
              <Stat value={String(stats.levels)} label="Levels" />
              <Stat value={String(stats.arrows)} label="Arrows" />
              <Stat value={stats.arrows ? `${(stats.ms / 1000 / stats.arrows).toFixed(2)} s` : '—'} label="Per arrow" />
            </View>
          </Card>

          <Card>
            <Text style={s.cardTitle}>Status</Text>
            <Text style={s.status}>{status || '—'}</Text>
          </Card>
        </ScrollView>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const s = StyleSheet.create({
  root: {flex: 1, backgroundColor: C.bg},
  content: {paddingHorizontal: 20, paddingTop: 24, paddingBottom: 32, gap: 16},
  title: {color: C.text, fontFamily: F.semi, fontSize: 30, letterSpacing: 0.2},
  titleArrow: {color: C.text, fontSize: 26},
  sub: {color: C.dim, fontFamily: F.medium, fontSize: 15, lineHeight: 22, marginBottom: 4},
  card: {backgroundColor: C.card, borderRadius: 22, paddingVertical: 6, overflow: 'hidden'},
  cardTitle: {color: C.text, fontFamily: F.semi, fontSize: 17, paddingHorizontal: 20, paddingTop: 12, paddingBottom: 4},
  row: {minHeight: 60, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 20, paddingVertical: 10, gap: 12},
  rowText: {flex: 1},
  rowLabel: {color: C.text, fontFamily: F.medium, fontSize: 17},
  rowHint: {color: C.dim, fontFamily: F.medium, fontSize: 13, marginTop: 2},
  chevron: {color: C.text, fontFamily: F.semi, fontSize: 26, lineHeight: 28},
  track: {width: 52, height: 30, borderRadius: 15, justifyContent: 'center', paddingHorizontal: 4},
  knob: {width: 22, height: 22, borderRadius: 11, backgroundColor: '#FFFFFF'},
  knobOn: {alignSelf: 'flex-end'},
  knobOff: {alignSelf: 'flex-start'},
  step: {flexDirection: 'row', gap: 12, paddingHorizontal: 20, paddingVertical: 8},
  stepNum: {
    width: 26,
    height: 26,
    borderRadius: 13,
    backgroundColor: C.raised,
    color: C.text,
    textAlign: 'center',
    textAlignVertical: 'center',
    fontFamily: F.semi,
    fontSize: 13,
  },
  stepText: {flex: 1, color: C.text, fontFamily: F.medium, fontSize: 15, lineHeight: 22},
  status: {color: C.text, fontFamily: F.medium, fontSize: 15, lineHeight: 22, paddingHorizontal: 20, paddingBottom: 12},
  stats: {flexDirection: 'row', paddingHorizontal: 12, paddingBottom: 12},
  stat: {flex: 1, alignItems: 'center', paddingVertical: 8},
  statValue: {color: C.text, fontFamily: F.semi, fontSize: 24},
  statLabel: {color: C.dim, fontFamily: F.medium, fontSize: 13, marginTop: 2},
  warn: {color: C.red, fontFamily: F.medium, fontSize: 14, paddingHorizontal: 20, paddingBottom: 10},
});
