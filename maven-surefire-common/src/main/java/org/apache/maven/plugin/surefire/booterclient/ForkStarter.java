package org.apache.maven.plugin.surefire.booterclient;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.surefire.CommonReflector;
import org.apache.maven.plugin.surefire.StartupReportConfiguration;
import org.apache.maven.plugin.surefire.SurefireProperties;
import org.apache.maven.plugin.surefire.booterclient.lazytestprovider.AbstractForkInputStream;
import org.apache.maven.plugin.surefire.booterclient.lazytestprovider.NotifiableTestStream;
import org.apache.maven.plugin.surefire.booterclient.lazytestprovider.OutputStreamFlushableCommandline;
import org.apache.maven.plugin.surefire.booterclient.lazytestprovider.TestLessInputStream;
import org.apache.maven.plugin.surefire.booterclient.lazytestprovider.TestProvidingInputStream;
import org.apache.maven.plugin.surefire.booterclient.output.ForkClient;
import org.apache.maven.plugin.surefire.booterclient.output.ThreadedStreamConsumer;
import org.apache.maven.plugin.surefire.report.DefaultReporterFactory;
import org.apache.maven.shared.utils.cli.CommandLineException;
import org.apache.maven.shared.utils.cli.CommandLineTimeOutException;
import org.apache.maven.surefire.booter.Classpath;
import org.apache.maven.surefire.booter.ClasspathConfiguration;
import org.apache.maven.surefire.booter.KeyValueSource;
import org.apache.maven.surefire.booter.PropertiesWrapper;
import org.apache.maven.surefire.booter.ProviderConfiguration;
import org.apache.maven.surefire.booter.ProviderFactory;
import org.apache.maven.surefire.booter.StartupConfiguration;
import org.apache.maven.surefire.booter.SurefireBooterForkException;
import org.apache.maven.surefire.booter.SurefireExecutionException;
import org.apache.maven.surefire.providerapi.SurefireProvider;
import org.apache.maven.surefire.report.StackTraceWriter;
import org.apache.maven.surefire.suite.RunResult;
import org.apache.maven.surefire.testset.TestRequest;
import org.apache.maven.surefire.util.DefaultScanResult;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.maven.shared.utils.cli.CommandLineUtils.executeCommandLine;
import static org.apache.maven.shared.utils.cli.ShutdownHookUtils.addShutDownHook;
import static org.apache.maven.shared.utils.cli.ShutdownHookUtils.removeShutdownHook;
import static org.apache.maven.surefire.util.internal.StringUtils.FORK_STREAM_CHARSET_NAME;
import static org.apache.maven.surefire.util.internal.DaemonThreadFactory.newDaemonThread;
import static org.apache.maven.surefire.util.internal.DaemonThreadFactory.newDaemonThreadFactory;
import static org.apache.maven.plugin.surefire.AbstractSurefireMojo.createCopyAndReplaceForkNumPlaceholder;
import static org.apache.maven.plugin.surefire.booterclient.lazytestprovider.
    TestLessInputStream.TestLessInputStreamBuilder;
import static org.apache.maven.surefire.util.internal.ConcurrencyUtils.countDownToZero;
import static org.apache.maven.surefire.booter.Classpath.join;
import static org.apache.maven.surefire.booter.SystemPropertyManager.writePropertiesFile;
import static org.apache.maven.surefire.suite.RunResult.timeout;
import static org.apache.maven.surefire.suite.RunResult.failure;
import static org.apache.maven.surefire.suite.RunResult.SUCCESS;
import static java.lang.StrictMath.min;

/**
 * Starts the fork or runs in-process.
 * <p/>
 * Lives only on the plugin-side (not present in remote vms)
 * <p/>
 * Knows how to fork new vms and also how to delegate non-forking invocation to SurefireStarter directly
 *
 * @author Jason van Zyl
 * @author Emmanuel Venisse
 * @author Brett Porter
 * @author Dan Fabulich
 * @author Carlos Sanchez
 * @author Kristian Rosenvold
 */
public class ForkStarter
{
    private final ThreadFactory threadFactory = newDaemonThreadFactory();

    /**
     * Closes an InputStream
     */
    private static class InputStreamCloser
        implements Runnable
    {
        private final AtomicReference<InputStream> testProvidingInputStream;

        public InputStreamCloser( InputStream testProvidingInputStream )
        {
            this.testProvidingInputStream = new AtomicReference<InputStream>( testProvidingInputStream );
        }

        public void run()
        {
            InputStream stream = testProvidingInputStream.getAndSet( null );
            if ( stream != null )
            {
                try
                {
                    stream.close();
                }
                catch ( IOException e )
                {
                    // ignore
                }
            }
        }
    }

    private final int forkedProcessTimeoutInSeconds;

    private final ProviderConfiguration providerConfiguration;

    private final StartupConfiguration startupConfiguration;

    private final ForkConfiguration forkConfiguration;

    private final StartupReportConfiguration startupReportConfiguration;

    private final Log log;

    private final DefaultReporterFactory defaultReporterFactory;

    private final Collection<DefaultReporterFactory> defaultReporterFactories;

    private static volatile int systemPropertiesFileCounter = 0;

    public ForkStarter( ProviderConfiguration providerConfiguration, StartupConfiguration startupConfiguration,
                        ForkConfiguration forkConfiguration, int forkedProcessTimeoutInSeconds,
                        StartupReportConfiguration startupReportConfiguration, Log log )
    {
        this.forkConfiguration = forkConfiguration;
        this.providerConfiguration = providerConfiguration;
        this.forkedProcessTimeoutInSeconds = forkedProcessTimeoutInSeconds;
        this.startupConfiguration = startupConfiguration;
        this.startupReportConfiguration = startupReportConfiguration;
        this.log = log;
        defaultReporterFactory = new DefaultReporterFactory( startupReportConfiguration );
        defaultReporterFactory.runStarting();
        defaultReporterFactories = new ConcurrentLinkedQueue<DefaultReporterFactory>();
    }

    public RunResult run( SurefireProperties effectiveSystemProperties, DefaultScanResult scanResult )
        throws SurefireBooterForkException, SurefireExecutionException
    {
        try
        {
            Map<String, String> providerProperties = providerConfiguration.getProviderProperties();
            scanResult.writeTo( providerProperties );
            return isForkOnce()
                    ? run( effectiveSystemProperties, providerProperties )
                    : run( effectiveSystemProperties );
        }
        finally
        {
            defaultReporterFactory.mergeFromOtherFactories( defaultReporterFactories );
            defaultReporterFactory.close();
        }
    }

    private RunResult run( SurefireProperties effectiveSystemProperties, Map<String, String> providerProperties )
            throws SurefireBooterForkException
    {
        DefaultReporterFactory forkedReporterFactory = new DefaultReporterFactory( startupReportConfiguration );
        defaultReporterFactories.add( forkedReporterFactory );
        PropertiesWrapper props = new PropertiesWrapper( providerProperties );
        ForkClient forkClient =
            new ForkClient( forkedReporterFactory, startupReportConfiguration.getTestVmSystemProperties() );
        TestLessInputStreamBuilder builder = new TestLessInputStreamBuilder();
        TestLessInputStream stream = builder.build();
        try
        {
            return fork( null, props, forkClient, effectiveSystemProperties, stream, false );
        }
        finally
        {
            builder.removeStream( stream );
        }
    }

    private RunResult run( SurefireProperties effectiveSystemProperties )
            throws SurefireBooterForkException
    {
        return forkConfiguration.isReuseForks()
                ? runSuitesForkOnceMultiple( effectiveSystemProperties, forkConfiguration.getForkCount() )
                : runSuitesForkPerTestSet( effectiveSystemProperties, forkConfiguration.getForkCount() );
    }

    private boolean isForkOnce()
    {
        return forkConfiguration.isReuseForks() && ( forkConfiguration.getForkCount() == 1 || hasSuiteXmlFiles() );
    }

    private boolean hasSuiteXmlFiles()
    {
        TestRequest testSuiteDefinition = providerConfiguration.getTestSuiteDefinition();
        return testSuiteDefinition != null && testSuiteDefinition.getSuiteXmlFiles() != null
            && !testSuiteDefinition.getSuiteXmlFiles().isEmpty();
    }

    @SuppressWarnings( "checkstyle:magicnumber" )
    private RunResult runSuitesForkOnceMultiple( final SurefireProperties effectiveSystemProperties, int forkCount )
        throws SurefireBooterForkException
    {
        ThreadPoolExecutor executorService = new ThreadPoolExecutor( forkCount, forkCount, 60, TimeUnit.SECONDS,
                                                                  new ArrayBlockingQueue<Runnable>( forkCount ) );
        executorService.setThreadFactory( threadFactory );
        try
        {
            final Queue<String> tests = new ConcurrentLinkedQueue<String>();

            for ( Class<?> clazz : getSuitesIterator() )
            {
                tests.add( clazz.getName() );
            }

            final Queue<TestProvidingInputStream> testStreams = new ConcurrentLinkedQueue<TestProvidingInputStream>();

            for ( int forkNum = 0, total = min( forkCount, tests.size() ); forkNum < total; forkNum++ )
            {
                testStreams.add( new TestProvidingInputStream( tests ) );
            }

            final AtomicInteger notifyStreamsToSkipTestsJustNow = new AtomicInteger();
            Collection<Future<RunResult>> results = new ArrayList<Future<RunResult>>( forkCount );
            for ( final TestProvidingInputStream testProvidingInputStream : testStreams )
            {
                Callable<RunResult> pf = new Callable<RunResult>()
                {
                    public RunResult call()
                        throws Exception
                    {
                        DefaultReporterFactory reporter = new DefaultReporterFactory( startupReportConfiguration );
                        defaultReporterFactories.add( reporter );

                        Properties vmProps = startupReportConfiguration.getTestVmSystemProperties();

                        ForkClient forkClient = new ForkClient( reporter, vmProps, testProvidingInputStream )
                        {
                            @Override
                            protected void stopOnNextTest()
                            {
                                if ( countDownToZero( notifyStreamsToSkipTestsJustNow ) )
                                {
                                    notifyStreamsToSkipTests( testStreams );
                                }
                            }
                        };

                        return fork( null, new PropertiesWrapper( providerConfiguration.getProviderProperties() ),
                                 forkClient, effectiveSystemProperties, testProvidingInputStream, true );
                    }
                };
                results.add( executorService.submit( pf ) );
            }
            return awaitResultsDone( results, executorService );
        }
        finally
        {
            closeExecutor( executorService );
        }
    }

    private static void notifyStreamsToSkipTests( Collection<? extends NotifiableTestStream> notifiableTestStreams )
    {
        for ( NotifiableTestStream notifiableTestStream : notifiableTestStreams )
        {
            notifiableTestStream.skipSinceNextTest();
        }
    }

    @SuppressWarnings( "checkstyle:magicnumber" )
    private RunResult runSuitesForkPerTestSet( final SurefireProperties effectiveSystemProperties, int forkCount )
        throws SurefireBooterForkException
    {
        ArrayList<Future<RunResult>> results = new ArrayList<Future<RunResult>>( 500 );
        ThreadPoolExecutor executorService =
            new ThreadPoolExecutor( forkCount, forkCount, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>() );
        executorService.setThreadFactory( threadFactory );
        try
        {
            int failFastCount = providerConfiguration.getSkipAfterFailureCount();
            final AtomicInteger notifyStreamsToSkipTestsJustNow = new AtomicInteger( failFastCount );
            final TestLessInputStreamBuilder builder = new TestLessInputStreamBuilder();
            for ( final Object testSet : getSuitesIterator() )
            {
                Callable<RunResult> pf = new Callable<RunResult>()
                {
                    public RunResult call()
                        throws Exception
                    {
                        DefaultReporterFactory forkedReporterFactory =
                            new DefaultReporterFactory( startupReportConfiguration );
                        defaultReporterFactories.add( forkedReporterFactory );
                        Properties vmProps = startupReportConfiguration.getTestVmSystemProperties();
                        ForkClient forkClient = new ForkClient( forkedReporterFactory, vmProps )
                        {
                            @Override
                            protected void stopOnNextTest()
                            {
                                if ( countDownToZero( notifyStreamsToSkipTestsJustNow ) )
                                {
                                    builder.getCachableCommands().skipSinceNextTest();
                                }
                            }
                        };
                        TestLessInputStream stream = builder.build();
                        try
                        {
                            return fork( testSet,
                                         new PropertiesWrapper( providerConfiguration.getProviderProperties() ),
                                         forkClient, effectiveSystemProperties, stream, false );
                        }
                        finally
                        {
                            builder.removeStream( stream );
                        }
                    }
                };
                results.add( executorService.submit( pf ) );
            }
            return awaitResultsDone( results, executorService );
        }
        finally
        {
            closeExecutor( executorService );
        }
    }

    private static RunResult awaitResultsDone( Collection<Future<RunResult>> results, ExecutorService executorService )
        throws SurefireBooterForkException
    {
        RunResult globalResult = new RunResult( 0, 0, 0, 0 );
        for ( Future<RunResult> result : results )
        {
            try
            {
                RunResult cur = result.get();
                if ( cur != null )
                {
                    globalResult = globalResult.aggregate( cur );
                }
                else
                {
                    throw new SurefireBooterForkException( "No results for " + result.toString() );
                }
            }
            catch ( InterruptedException e )
            {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
                throw new SurefireBooterForkException( "Interrupted", e );
            }
            catch ( ExecutionException e )
            {
                Throwable realException = e.getCause();
                String error = realException == null ? "" : realException.getLocalizedMessage();
                throw new SurefireBooterForkException( "ExecutionException " + error, realException );
            }
        }
        return globalResult;
    }

    @SuppressWarnings( "checkstyle:magicnumber" )
    private void closeExecutor( ExecutorService executorService )
        throws SurefireBooterForkException
    {
        executorService.shutdown();
        try
        {
            // Should stop immediately, as we got all the results if we are here
            executorService.awaitTermination( 60 * 60, TimeUnit.SECONDS );
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            throw new SurefireBooterForkException( "Interrupted", e );
        }
    }

    private RunResult fork( Object testSet, KeyValueSource providerProperties, ForkClient forkClient,
                            SurefireProperties effectiveSystemProperties,
                            AbstractForkInputStream testProvidingInputStream, boolean readTestsFromInStream )
        throws SurefireBooterForkException
    {
        int forkNumber = ForkNumberBucket.drawNumber();
        try
        {
            return fork( testSet, providerProperties, forkClient, effectiveSystemProperties, forkNumber,
                         testProvidingInputStream, readTestsFromInStream );
        }
        finally
        {
            ForkNumberBucket.returnNumber( forkNumber );
        }
    }

    private RunResult fork( Object testSet, KeyValueSource providerProperties, ForkClient forkClient,
                            SurefireProperties effectiveSystemProperties, int forkNumber,
                            AbstractForkInputStream testProvidingInputStream, boolean readTestsFromInStream )
        throws SurefireBooterForkException
    {
        final File surefireProperties;
        final File systPropsFile;
        try
        {
            BooterSerializer booterSerializer = new BooterSerializer( forkConfiguration );

            surefireProperties = booterSerializer.serialize( providerProperties, providerConfiguration,
                                                             startupConfiguration, testSet, readTestsFromInStream );

            if ( effectiveSystemProperties != null )
            {
                SurefireProperties filteredProperties =
                    createCopyAndReplaceForkNumPlaceholder( effectiveSystemProperties, forkNumber );

                systPropsFile = writePropertiesFile( filteredProperties, forkConfiguration.getTempDirectory(),
                                                     "surefire_" + systemPropertiesFileCounter++,
                                                     forkConfiguration.isDebug() );
            }
            else
            {
                systPropsFile = null;
            }
        }
        catch ( IOException e )
        {
            throw new SurefireBooterForkException( "Error creating properties files for forking", e );
        }

        // this could probably be simplified further
        final Classpath bootClasspathConfiguration = startupConfiguration.isProviderMainClass()
            ? startupConfiguration.getClasspathConfiguration().getProviderClasspath()
            : forkConfiguration.getBootClasspath();

        Classpath bootClasspath = join(
            join( bootClasspathConfiguration, startupConfiguration.getClasspathConfiguration().getTestClasspath() ),
            startupConfiguration.getClasspathConfiguration().getProviderClasspath() );

        if ( log.isDebugEnabled() )
        {
            log.debug( bootClasspath.getLogMessage( "boot" ) );
            log.debug( bootClasspath.getCompactLogMessage( "boot(compact)" ) );
        }

        OutputStreamFlushableCommandline cli =
            forkConfiguration.createCommandLine( bootClasspath.getClassPath(), startupConfiguration, forkNumber );

        InputStreamCloser inputStreamCloser = new InputStreamCloser( testProvidingInputStream );
        Thread inputStreamCloserHook = newDaemonThread( inputStreamCloser, "input-stream-closer" );
        testProvidingInputStream.setFlushReceiverProvider( cli );
        addShutDownHook( inputStreamCloserHook );

        cli.createArg().setFile( surefireProperties );

        if ( systPropsFile != null )
        {
            cli.createArg().setFile( systPropsFile );
        }

        ThreadedStreamConsumer threadedStreamConsumer = new ThreadedStreamConsumer( forkClient );

        if ( forkConfiguration.isDebug() )
        {
            System.out.println( "Forking command line: " + cli );
        }

        RunResult runResult = null;

        try
        {
            final int timeout = forkedProcessTimeoutInSeconds > 0 ? forkedProcessTimeoutInSeconds : 0;

            final int result = executeCommandLine( cli, testProvidingInputStream, threadedStreamConsumer,
                                                   threadedStreamConsumer, timeout, inputStreamCloser,
                                                   Charset.forName( FORK_STREAM_CHARSET_NAME ) );

            if ( result != SUCCESS )
            {
                throw new SurefireBooterForkException( "Error occurred in starting fork, check output in log" );
            }

        }
        catch ( CommandLineTimeOutException e )
        {
            runResult = timeout( forkClient.getDefaultReporterFactory().getGlobalRunStatistics().getRunResult() );
        }
        catch ( CommandLineException e )
        {
            runResult = failure( forkClient.getDefaultReporterFactory().getGlobalRunStatistics().getRunResult(), e );
            throw new SurefireBooterForkException( "Error while executing forked tests.", e.getCause() );
        }
        finally
        {
            threadedStreamConsumer.close();
            inputStreamCloser.run();
            removeShutdownHook( inputStreamCloserHook );

            if ( runResult == null )
            {
                runResult = forkClient.getDefaultReporterFactory().getGlobalRunStatistics().getRunResult();
            }

            if ( !runResult.isTimeout() )
            {
                StackTraceWriter errorInFork = forkClient.getErrorInFork();
                if ( errorInFork != null )
                {
                    // noinspection ThrowFromFinallyBlock
                    throw new RuntimeException(
                        "There was an error in the forked process\n" + errorInFork.writeTraceToString() );
                }
                if ( !forkClient.isSaidGoodBye() )
                {
                    // noinspection ThrowFromFinallyBlock
                    throw new RuntimeException(
                        "The forked VM terminated without properly saying goodbye. VM crash or System.exit called?"
                            + "\nCommand was " + cli.toString() );
                }

            }
            forkClient.close( runResult.isTimeout() );
        }

        return runResult;
    }

    private Iterable<Class<?>> getSuitesIterator()
        throws SurefireBooterForkException
    {
        try
        {
            final ClasspathConfiguration classpathConfiguration = startupConfiguration.getClasspathConfiguration();
            ClassLoader unifiedClassLoader = classpathConfiguration.createMergedClassLoader();

            CommonReflector commonReflector = new CommonReflector( unifiedClassLoader );
            Object reporterFactory = commonReflector.createReportingReporterFactory( startupReportConfiguration );

            ProviderFactory providerFactory =
                new ProviderFactory( startupConfiguration, providerConfiguration, unifiedClassLoader, reporterFactory );
            SurefireProvider surefireProvider = providerFactory.createProvider( false );
            return surefireProvider.getSuites();
        }
        catch ( SurefireExecutionException e )
        {
            throw new SurefireBooterForkException( "Unable to create classloader to find test suites", e );
        }
    }
}
