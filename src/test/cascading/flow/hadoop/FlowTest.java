/*
 * Copyright (c) 2007-2011 Concurrent, Inc. All Rights Reserved.
 *
 * Project and contact information: http://www.cascading.org/
 *
 * This file is part of the Cascading project.
 *
 * Cascading is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Cascading is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Cascading.  If not, see <http://www.gnu.org/licenses/>.
 */

package cascading.flow.hadoop;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import cascading.PlatformTestCase;
import cascading.flow.FailingFlowListener;
import cascading.flow.Flow;
import cascading.flow.FlowProcess;
import cascading.flow.LockingFlowListener;
import cascading.flow.planner.FlowStep;
import cascading.operation.BaseOperation;
import cascading.operation.Debug;
import cascading.operation.Filter;
import cascading.operation.FilterCall;
import cascading.operation.Function;
import cascading.operation.regex.RegexSplitter;
import cascading.pipe.CoGroup;
import cascading.pipe.Each;
import cascading.pipe.GroupBy;
import cascading.pipe.Pipe;
import cascading.scheme.hadoop.TextLine;
import cascading.tap.Tap;
import cascading.tap.hadoop.Hfs;
import cascading.tap.hadoop.Lfs;
import cascading.test.PlatformTest;
import cascading.tuple.Fields;

import static data.InputData.inputFileLower;
import static data.InputData.inputFileUpper;

/**
 *
 */
@PlatformTest(platforms = {"hadoop"})
public class FlowTest extends PlatformTestCase
  {
  public FlowTest()
    {
    super( true ); // must be run in cluster mode
    }

  // test is not executed, just guarantees flow is run locally

  public void testLocalModeSource() throws Exception
    {
    Tap source = new Lfs( new TextLine(), "input/path" );
    Tap sink = new Hfs( new TextLine(), "output/path", true );

    Pipe pipe = new Pipe( "test" );

    Map<Object, Object> props = getProperties();
    Flow flow = new HadoopFlowConnector( props ).connect( source, sink, pipe );

    List<FlowStep> steps = flow.getSteps();

    assertEquals( "wrong size", 1, steps.size() );

    FlowStep step = (FlowStep) steps.get( 0 );

    String tracker = ( (HadoopFlowStep) step ).getInitializedConfig( flow.getFlowProcess(), HadoopPlanner.getJobConf( props ) ).get( "mapred.job.tracker" );
    boolean isLocal = tracker.equalsIgnoreCase( "local" );

    assertTrue( "is not local", isLocal );
    }

  // test is not executed, just guarantees flow is run locally

  public void testLocalModeSink() throws Exception
    {
    Tap source = new Hfs( new TextLine(), "input/path" );
    Tap sink = new Lfs( new TextLine(), "output/path", true );

    Pipe pipe = new Pipe( "test" );

    Map<Object, Object> props = getProperties();
    Flow flow = new HadoopFlowConnector( props ).connect( source, sink, pipe );

    List<FlowStep> steps = flow.getSteps();

    assertEquals( "wrong size", 1, steps.size() );

    FlowStep step = (FlowStep) steps.get( 0 );

    String tracker = ( (HadoopFlowStep) step ).getInitializedConfig( flow.getFlowProcess(), HadoopPlanner.getJobConf( props ) ).get( "mapred.job.tracker" );
    boolean isLocal = tracker.equalsIgnoreCase( "local" );

    assertTrue( "is not local", isLocal );
    }

  // test is not executed, just guarantees flow is run on cluster

  public void testNotLocalMode() throws Exception
    {
    Tap source = new Hfs( new TextLine(), "input/path" );
    Tap sink = new Hfs( new TextLine(), "output/path", true );

    Pipe pipe = new Pipe( "test" );

    Map<Object, Object> props = getProperties();
    Flow flow = new HadoopFlowConnector( props ).connect( source, sink, pipe );

    List<FlowStep> steps = flow.getSteps();

    assertEquals( "wrong size", 1, steps.size() );

    FlowStep step = (FlowStep) steps.get( 0 );

    String tracker = ( (HadoopFlowStep) step ).getInitializedConfig( flow.getFlowProcess(), HadoopPlanner.getJobConf( props ) ).get( "mapred.job.tracker" );
    boolean isLocal = tracker.equalsIgnoreCase( "local" );

    assertTrue( "is local", !isLocal );
    }

  public void testStop() throws Exception
    {
    getPlatform().copyFromLocal( inputFileLower );
    getPlatform().copyFromLocal( inputFileUpper );

    Tap sourceLower = new Hfs( new TextLine( new Fields( "offset", "line" ) ), inputFileLower );
    Tap sourceUpper = new Hfs( new TextLine( new Fields( "offset", "line" ) ), inputFileUpper );

    Map sources = new HashMap();

    sources.put( "lower", sourceLower );
    sources.put( "upper", sourceUpper );

    Function splitter = new RegexSplitter( new Fields( "num", "char" ), " " );

    // using null pos so all fields are written
    Tap sink = new Hfs( new TextLine(), getOutputPath( "stopped" ), true );

    Pipe pipeLower = new Each( new Pipe( "lower" ), new Fields( "line" ), splitter );

    pipeLower = new GroupBy( pipeLower, new Fields( "num" ) );

    Pipe pipeUpper = new Each( new Pipe( "upper" ), new Fields( "line" ), splitter );

    pipeUpper = new GroupBy( pipeUpper, new Fields( "num" ) );

    Pipe splice = new CoGroup( pipeLower, new Fields( "num" ), pipeUpper, new Fields( "num" ), Fields.size( 4 ) );

    Flow flow = new HadoopFlowConnector( getProperties() ).connect( sources, sink, splice );

//    countFlow.writeDOT( "stopped.dot" );

    LockingFlowListener listener = new LockingFlowListener();

    flow.addListener( listener );

    System.out.println( "calling start" );
    flow.start();

    assertTrue( "did not start", listener.started.tryAcquire( 60, TimeUnit.SECONDS ) );

    while( true )
      {
      System.out.println( "testing if running" );
      Thread.sleep( 1000 );

      Map<String, Callable<Throwable>> map = ( (Flow) flow ).getJobsMap();

      if( map == null || map.values().size() == 0 )
        continue;

      if( ( (HadoopFlowStepJob) map.values().iterator().next() ).isStarted() )
        break;
      }

    System.out.println( "calling stop" );

    flow.stop();

    assertTrue( "did not stop", listener.stopped.tryAcquire( 60, TimeUnit.SECONDS ) );
    assertTrue( "did not complete", listener.completed.tryAcquire( 60, TimeUnit.SECONDS ) );
    }

  private static class BadFilter extends BaseOperation implements Filter
    {
    private Object object = new Object(); // intentional

    public boolean isRemove( FlowProcess flowProcess, FilterCall filterCall )
      {
      return false;
      }
    }

  public void testFailedSerialization() throws Exception
    {
    getPlatform().copyFromLocal( inputFileLower );

    Tap sourceLower = new Hfs( new TextLine( new Fields( "offset", "line" ) ), inputFileLower );

    Map sources = new HashMap();

    sources.put( "lower", sourceLower );

    Function splitter = new RegexSplitter( new Fields( "num", "char" ), " " );

    // using null pos so all fields are written
    Tap sink = new Hfs( new TextLine(), getOutputPath( "badserialization" ), true );

    Pipe pipeLower = new Each( new Pipe( "lower" ), new Fields( "line" ), splitter );

    pipeLower = new Each( pipeLower, new Fields( "num" ), new BadFilter() );

    pipeLower = new GroupBy( pipeLower, new Fields( "num" ) );

    try
      {
      // assembly serialization now happens during Flow construction, no chance to use a listener to catch
      Flow flow = new HadoopFlowConnector( getProperties() ).connect( sources, sink, pipeLower );
      fail( "did not throw serialization exception" );
      }
    catch( Exception exception )
      {
      // ignore
      }
    }

  public void testFailingListenerStarting() throws Exception
    {
    failingListenerTest( FailingFlowListener.OnFail.STARTING );
    }

  public void testFailingListenerStopping() throws Exception
    {
    failingListenerTest( FailingFlowListener.OnFail.STOPPING );
    }

  public void testFailingListenerCompleted() throws Exception
    {
    failingListenerTest( FailingFlowListener.OnFail.COMPLETED );
    }

  public void testFailingListenerThrowable() throws Exception
    {
    failingListenerTest( FailingFlowListener.OnFail.THROWABLE );
    }

  public void failingListenerTest( FailingFlowListener.OnFail onFail ) throws Exception
    {
    getPlatform().copyFromLocal( inputFileLower );
    getPlatform().copyFromLocal( inputFileUpper );

    Tap sourceLower = new Hfs( new TextLine( new Fields( "offset", "line" ) ), inputFileLower );
    Tap sourceUpper = new Hfs( new TextLine( new Fields( "offset", "line" ) ), inputFileUpper );

    Map sources = new HashMap();

    sources.put( "lower", sourceLower );
    sources.put( "upper", sourceUpper );

    Function splitter = new RegexSplitter( new Fields( "num", "char" ), " " );

    // using null pos so all fields are written
    Tap sink = new Hfs( new TextLine(), getOutputPath( onFail + "/stopped" ), true );

    Pipe pipeLower = new Each( new Pipe( "lower" ), new Fields( "line" ), splitter );

    if( onFail == FailingFlowListener.OnFail.THROWABLE )
      {
      pipeLower = new Each( pipeLower, new Debug()
      {
      @Override
      public boolean isRemove( FlowProcess flowProcess, FilterCall filterCall )
        {
        throw new RuntimeException( "failing inside pipe assembly intentionally" );
        }
      } );
      }

    pipeLower = new GroupBy( pipeLower, new Fields( "num" ) );

    Pipe pipeUpper = new Each( new Pipe( "upper" ), new Fields( "line" ), splitter );

    pipeUpper = new GroupBy( pipeUpper, new Fields( "num" ) );

    Pipe splice = new CoGroup( pipeLower, new Fields( "num" ), pipeUpper, new Fields( "num" ), Fields.size( 4 ) );

    Flow flow = new HadoopFlowConnector( getProperties() ).connect( sources, sink, splice );

//    countFlow.writeDOT( "stopped.dot" );

    FailingFlowListener listener = new FailingFlowListener( onFail );

    flow.addListener( listener );

    System.out.println( "calling start" );
    flow.start();

    assertTrue( "did not start", listener.started.tryAcquire( 120, TimeUnit.SECONDS ) );

    if( onFail == FailingFlowListener.OnFail.STOPPING )
      {
      while( true )
        {
        System.out.println( "testing if running" );
        Thread.sleep( 1000 );

        Map<String, Callable<Throwable>> map = flow.getJobsMap();

        if( map == null || map.values().size() == 0 )
          continue;

        if( ( (HadoopFlowStepJob) map.values().iterator().next() ).isStarted() )
          break;
        }

      System.out.println( "calling stop" );

      flow.stop();
      }

    assertTrue( "did not complete", listener.completed.tryAcquire( 120, TimeUnit.SECONDS ) );
    assertTrue( "did not stop", listener.stopped.tryAcquire( 120, TimeUnit.SECONDS ) );

    try
      {
      flow.complete();
      fail( "did not rethrow exception from listener" );
      }
    catch( Exception exception )
      {
      // ignore
      }
    }

  public void testFlowID() throws Exception
    {
    Tap source = new Lfs( new TextLine(), "input/path" );
    Tap sink = new Hfs( new TextLine(), "output/path", true );

    Pipe pipe = new Pipe( "test" );

    Map<Object, Object> props = getProperties();
    Flow flow1 = new HadoopFlowConnector( props ).connect( source, sink, pipe );

//    System.out.println( "flow.getID() = " + flow1.getID() );

    assertNotNull( "missing id", flow1.getID() );
    assertNotNull( "missing id in conf", ( (HadoopFlow) flow1 ).getConfig().get( "cascading.flow.id" ) );

    Flow flow2 = new HadoopFlowConnector( props ).connect( source, sink, pipe );

    assertTrue( "same id", !flow1.getID().equalsIgnoreCase( flow2.getID() ) );
    }

  }