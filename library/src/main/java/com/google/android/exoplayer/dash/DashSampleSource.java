/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer.dash;

import com.google.android.exoplayer.C;
import com.google.android.exoplayer.DefaultLoadControl;
import com.google.android.exoplayer.LoadControl;
import com.google.android.exoplayer.SampleSource;
import com.google.android.exoplayer.TrackGroup;
import com.google.android.exoplayer.TrackGroupArray;
import com.google.android.exoplayer.TrackSelection;
import com.google.android.exoplayer.TrackStream;
import com.google.android.exoplayer.chunk.ChunkSampleSource;
import com.google.android.exoplayer.chunk.ChunkSampleSourceEventListener;
import com.google.android.exoplayer.chunk.ChunkSource;
import com.google.android.exoplayer.chunk.FormatEvaluator.AdaptiveEvaluator;
import com.google.android.exoplayer.dash.mpd.MediaPresentationDescription;
import com.google.android.exoplayer.dash.mpd.MediaPresentationDescriptionParser;
import com.google.android.exoplayer.upstream.BandwidthMeter;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DataSourceFactory;
import com.google.android.exoplayer.upstream.DefaultAllocator;
import com.google.android.exoplayer.util.Assertions;
import com.google.android.exoplayer.util.ManifestFetcher;

import android.net.Uri;
import android.os.Handler;
import android.util.Pair;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Combines multiple {@link SampleSource} instances.
 */
public final class DashSampleSource implements SampleSource {

  private final SampleSource[] sources;
  private final IdentityHashMap<TrackStream, SampleSource> trackStreamSources;
  private final int[] selectedTrackCounts;

  private boolean prepared;
  private boolean seenFirstTrackSelection;
  private long durationUs;
  private TrackGroupArray trackGroups;
  private SampleSource[] enabledSources;

  public DashSampleSource(Uri uri, DataSourceFactory dataSourceFactory,
      BandwidthMeter bandwidthMeter, Handler eventHandler,
      ChunkSampleSourceEventListener eventListener) {
    MediaPresentationDescriptionParser parser = new MediaPresentationDescriptionParser();
    DataSource manifestDataSource = dataSourceFactory.createDataSource();
    // TODO[REFACTOR]: This needs releasing.
    ManifestFetcher<MediaPresentationDescription> manifestFetcher = new ManifestFetcher<>(uri,
        manifestDataSource, parser);

    LoadControl loadControl = new DefaultLoadControl(
        new DefaultAllocator(C.DEFAULT_BUFFER_SEGMENT_SIZE));

    // Build the video renderer.
    DataSource videoDataSource = dataSourceFactory.createDataSource(bandwidthMeter);
    ChunkSource videoChunkSource = new DashChunkSource(manifestFetcher, C.TRACK_TYPE_VIDEO,
        videoDataSource, new AdaptiveEvaluator(bandwidthMeter));
    ChunkSampleSource videoSampleSource = new ChunkSampleSource(videoChunkSource, loadControl,
        C.DEFAULT_VIDEO_BUFFER_SIZE, eventHandler, eventListener, C.TRACK_TYPE_VIDEO);

    // Build the audio renderer.
    DataSource audioDataSource = dataSourceFactory.createDataSource(bandwidthMeter);
    ChunkSource audioChunkSource = new DashChunkSource(manifestFetcher, C.TRACK_TYPE_AUDIO,
        audioDataSource, null);
    ChunkSampleSource audioSampleSource = new ChunkSampleSource(audioChunkSource, loadControl,
        C.DEFAULT_AUDIO_BUFFER_SIZE, eventHandler, eventListener, C.TRACK_TYPE_AUDIO);

    // Build the text renderer.
    DataSource textDataSource = dataSourceFactory.createDataSource(bandwidthMeter);
    ChunkSource textChunkSource = new DashChunkSource(manifestFetcher, C.TRACK_TYPE_TEXT,
        textDataSource, null);
    ChunkSampleSource textSampleSource = new ChunkSampleSource(textChunkSource, loadControl,
        C.DEFAULT_TEXT_BUFFER_SIZE, eventHandler, eventListener, C.TRACK_TYPE_TEXT);

    sources = new SampleSource[] {videoSampleSource, audioSampleSource, textSampleSource};
    trackStreamSources = new IdentityHashMap<>();
    selectedTrackCounts = new int[sources.length];
  }

  @Override
  public boolean prepare(long positionUs) throws IOException {
    if (prepared) {
      return true;
    }
    boolean sourcesPrepared = true;
    for (SampleSource source : sources) {
      sourcesPrepared &= source.prepare(positionUs);
    }
    if (!sourcesPrepared) {
      return false;
    }
    durationUs = 0;
    int totalTrackGroupCount = 0;
    for (SampleSource source : sources) {
      totalTrackGroupCount += source.getTrackGroups().length;
      if (durationUs != C.UNSET_TIME_US) {
        long sourceDurationUs = source.getDurationUs();
        durationUs = sourceDurationUs == C.UNSET_TIME_US
            ? C.UNSET_TIME_US : Math.max(durationUs, sourceDurationUs);
      }
    }
    TrackGroup[] trackGroupArray = new TrackGroup[totalTrackGroupCount];
    int trackGroupIndex = 0;
    for (SampleSource source : sources) {
      int sourceTrackGroupCount = source.getTrackGroups().length;
      for (int j = 0; j < sourceTrackGroupCount; j++) {
        trackGroupArray[trackGroupIndex++] = source.getTrackGroups().get(j);
      }
    }
    trackGroups = new TrackGroupArray(trackGroupArray);
    prepared = true;
    return true;
  }

  @Override
  public long getDurationUs() {
    return durationUs;
  }

  @Override
  public TrackGroupArray getTrackGroups() {
    return trackGroups;
  }

  @Override
  public TrackStream[] selectTracks(List<TrackStream> oldStreams,
      List<TrackSelection> newSelections, long positionUs) {
    Assertions.checkState(prepared);
    TrackStream[] newStreams = new TrackStream[newSelections.size()];
    // Select tracks for each source.
    int enabledSourceCount = 0;
    for (int i = 0; i < sources.length; i++) {
      selectedTrackCounts[i] += selectTracks(sources[i], oldStreams, newSelections, positionUs,
          newStreams);
      if (selectedTrackCounts[i] > 0) {
        enabledSourceCount++;
      }
    }
    // Update the enabled sources.
    enabledSources = new SampleSource[enabledSourceCount];
    enabledSourceCount = 0;
    for (int i = 0; i < sources.length; i++) {
      if (selectedTrackCounts[i] > 0) {
        enabledSources[enabledSourceCount++] = sources[i];
      }
    }
    seenFirstTrackSelection = true;
    return newStreams;
  }

  @Override
  public void continueBuffering(long positionUs) {
    for (SampleSource source : enabledSources) {
      source.continueBuffering(positionUs);
    }
  }

  @Override
  public long readReset() {
    long resetPositionUs = C.UNSET_TIME_US;
    for (SampleSource source : enabledSources) {
      long childResetPositionUs = source.readReset();
      if (resetPositionUs == C.UNSET_TIME_US) {
        resetPositionUs = childResetPositionUs;
      } else if (childResetPositionUs != C.UNSET_TIME_US) {
        resetPositionUs = Math.min(resetPositionUs, childResetPositionUs);
      }
    }
    return resetPositionUs;
  }

  @Override
  public long getBufferedPositionUs() {
    long bufferedPositionUs = durationUs != C.UNSET_TIME_US ? durationUs : Long.MAX_VALUE;
    for (SampleSource source : enabledSources) {
      long rendererBufferedPositionUs = source.getBufferedPositionUs();
      if (rendererBufferedPositionUs == C.UNSET_TIME_US) {
        return C.UNSET_TIME_US;
      } else if (rendererBufferedPositionUs == C.END_OF_SOURCE_US) {
        // This source is fully buffered.
      } else {
        bufferedPositionUs = Math.min(bufferedPositionUs, rendererBufferedPositionUs);
      }
    }
    return bufferedPositionUs == Long.MAX_VALUE ? C.UNSET_TIME_US : bufferedPositionUs;
  }

  @Override
  public void seekToUs(long positionUs) {
    for (SampleSource source : enabledSources) {
      source.seekToUs(positionUs);
    }
  }

  @Override
  public void release() {
    for (SampleSource source : sources) {
      source.release();
    }
  }

  // Internal methods.

  private int selectTracks(SampleSource source, List<TrackStream> allOldStreams,
      List<TrackSelection> allNewSelections, long positionUs, TrackStream[] allNewStreams) {
    // Get the subset of the old streams for the source.
    ArrayList<TrackStream> oldStreams = new ArrayList<>();
    for (int i = 0; i < allOldStreams.size(); i++) {
      TrackStream stream = allOldStreams.get(i);
      if (trackStreamSources.get(stream) == source) {
        trackStreamSources.remove(stream);
        oldStreams.add(stream);
      }
    }
    // Get the subset of the new selections for the source.
    ArrayList<TrackSelection> newSelections = new ArrayList<>();
    int[] newSelectionOriginalIndices = new int[allNewSelections.size()];
    for (int i = 0; i < allNewSelections.size(); i++) {
      TrackSelection selection = allNewSelections.get(i);
      Pair<SampleSource, Integer> sourceAndGroup = getSourceAndGroup(selection.group);
      if (sourceAndGroup.first == source) {
        newSelectionOriginalIndices[newSelections.size()] = i;
        newSelections.add(new TrackSelection(sourceAndGroup.second, selection.getTracks()));
      }
    }
    // Do nothing if nothing has changed, except during the first selection.
    if (seenFirstTrackSelection && oldStreams.isEmpty() && newSelections.isEmpty()) {
      return 0;
    }
    // Perform the selection.
    TrackStream[] newStreams = source.selectTracks(oldStreams, newSelections, positionUs);
    for (int j = 0; j < newStreams.length; j++) {
      allNewStreams[newSelectionOriginalIndices[j]] = newStreams[j];
      trackStreamSources.put(newStreams[j], source);
    }
    return newSelections.size() - oldStreams.size();
  }

  private Pair<SampleSource, Integer> getSourceAndGroup(int group) {
    int totalTrackGroupCount = 0;
    for (SampleSource source : sources) {
      int sourceTrackGroupCount = source.getTrackGroups().length;
      if (group < totalTrackGroupCount + sourceTrackGroupCount) {
        return Pair.create(source, group - totalTrackGroupCount);
      }
      totalTrackGroupCount += sourceTrackGroupCount;
    }
    throw new IndexOutOfBoundsException();
  }

}
