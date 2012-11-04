/**
 * @file    QuickSortParalelo.java
 * @brief   Implementa a versão paralela do algoritmo Quicksort, descrita em
 * [Kale e Solomonik 2010], em MapReduce no ambiente Hadoop.
 *
 * @author  Paula Pinhão
 * @year    2011
 * @version 1.0
*/

package quicksorthadoop;

// <editor-fold defaultstate="collapsed" desc="Bibliotecas">
import customkey.IntPair;
import java.io.IOException;
import java.util.Iterator;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.WritableComparator;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Partitioner;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.mapred.TextOutputFormat;
import org.apache.hadoop.util.ToolRunner;
// </editor-fold>

public class QuickSortHadoop
{
    final static int valor_max = 20;

    // num_maquinas * num_nucleos
    final static  int num_tarefas_reduce = 4;

    // <editor-fold defaultstate="collapsed" desc="Calcula Particao">
    /**
     * @param valor
     * @return
     */
    public static int calculaParticao(int valor)
    {
        return valor / ((valor_max / num_tarefas_reduce) + 1);
    }

    // </editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Comparacoes">
    /** Classe Comparacao de Chaves
     * Compara as chaves de cada particao
     */
    public class ComparacaoChaves extends WritableComparator
    {
        protected ComparacaoChaves() { super(IntPair.class, true); }

        @Override
        public int compare(WritableComparable w1, WritableComparable w2)
        {
            System.out.println("compare chave wc");

            IntPair pair1 = (IntPair) w1;
            IntPair pair2 = (IntPair) w2;

            int cmp = compare(pair1.getFirst(), pair2.getFirst());

            if(cmp != 0) { return cmp; }

            return compare(pair1.getSecond(), pair2.getSecond());
        }
    }

    /** Classe Comparacao de Valores
     * Compara os valores de cada particao e assim determina para qual reducer
     * a saida do mapper deve ir (ordena a entrada do reducer)
     */
    public class ComparacaoValores extends WritableComparator
    {
        protected ComparacaoValores() { super(IntPair.class, true); }

        @Override
        public int compare(WritableComparable w1, WritableComparable w2)
        {         
            IntPair pair1 = (IntPair) w1;
            IntPair pair2 = (IntPair) w2;
            return compare(pair1.getFirst(), pair2.getFirst());
        }
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="Particionamento">
    /** Classe de Particionamento
     * Implementa os metodos da interface Partitioner
     */
    public static final class Particionamento implements Partitioner<IntPair, NullWritable>
    {
        /** Determina a faixa de valores das particoes
         * @param key chave
         * @param value valor inteiro
         * @param numPartitions numero de particoes = numero de tarefas reduce
         * @return numero da particao para a qual o dado sera deslocado
         */
        public int getPartition(IntPair key, NullWritable value, int numPartitions)
        {
            return QuickSortHadoop.calculaParticao(key.getSecond().get());
        }

        public void configure(JobConf job) {}
    }// </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="Mapeamento">
    /** Classe de Mapeamento
     * Implementa os metodos da interface Mapper
     */
    public static final class Mapeamento implements Mapper<LongWritable, Text, IntPair, NullWritable>
    {
        /** Le o arquivo e forma os pares (chave, valor)
         * @param key byte offset da linha do arquivo
         * @param value conteudo da linha do arquivo
         * @param collector pares (key, value)
         * @param reporter informacoes sobre a tarefa atual
         * @throws IOException
         */
        public void map(LongWritable key, Text value, OutputCollector<IntPair, NullWritable> collector, Reporter reporter) throws IOException
        {
            String[] linha = value.toString().split(" ");
            System.err.println("-> Ordenando "+ linha.length + " dados.");

            for(int i = 0; i < linha.length; i++)
            {
                IntWritable valor = new IntWritable(Integer.parseInt(linha[i]));
                collector.collect(new IntPair(new IntWritable(calculaParticao(valor.get())), valor), NullWritable.get());
            }
        }

        public void configure(JobConf conf) {}

        public void close() throws IOException {}
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="Reducao">
    /** Classe de Reducao
     * Implementa os metodos da interface Reducer
     */
    public static final class Reducao implements Reducer<IntPair, NullWritable, IntPair, NullWritable>
    {
        /** Concatena as particoes, uma vez que ja estao ordenadas
         * @param key
         * @param values
         * @param collector
         * @param reporter
         * @throws IOException
         */
        public void reduce(IntPair key, Iterator<NullWritable> values, OutputCollector<IntPair, NullWritable> collector, Reporter reporter) throws IOException
        {
	    while(values.hasNext())
            {
		//collector.collect(key, values.next());
                collector.collect(key, NullWritable.get());
	    }
        }

        public void configure(JobConf conf) {}

        public void close() throws IOException {}
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="Configuracoes">
    /** Inicializa o job e seta as configuracoes a serem utilizadas pela plataforma
     * @param args[0] nome do arquivo de entrada
     * @param args[1] nome do arquivo de saida
     * @return -1 (erro) ou 0 (sucesso)
     * @throws IOException
     */
    public int run(String[] args) throws IOException
    {
        if (args.length != 2)
        {
            System.err.printf("Uso: %s [opcoes genericas] <entrada> <saida>\n", getClass().getSimpleName());
            ToolRunner.printGenericCommandUsage(System.err);
            return -1;
        }
        else
        {
            // carrega os arquivos de configuracao da pasta conf do Hadoop
            JobConf conf = new JobConf();
            conf.setJobName("QuickSortHaddop");

            // especifica a classe do programa principal
            conf.setJarByClass(QuickSortHadoop.class);

            // numero de tarefas reduce
            conf.setNumReduceTasks(num_tarefas_reduce);

            // especifica os formatos (chave, valor) dos arquivos de entrada e saida
            conf.setInputFormat(TextInputFormat.class);
            conf.setOutputFormat(TextOutputFormat.class);

            // especifica os tipos de chave e valor dos pares intermediarios (mapper)
            conf.setMapOutputKeyClass(IntPair.class);
            conf.setMapOutputValueClass(IntWritable.class);

            // especifica os tipos de chave e valor dos pares finais resultantes (reducer)
            conf.setOutputKeyClass(IntPair.class);
            conf.setOutputValueClass(NullWritable.class);

            // especifica as classes de mapeamento e de reducao
            conf.setMapperClass(Mapeamento.class);
            conf.setReducerClass(Reducao.class);

            // especifica as classes para implementacao da ordenacao
            conf.setPartitionerClass(Particionamento.class);
            //conf.setOutputKeyComparatorClass(ComparacaoChaves.class);
            //conf.setOutputValueGroupingComparator(ComparacaoValores.class);

            // especifica os caminhos dos arquivos de entrada e saida no HDFS
            FileInputFormat.addInputPath(conf, new Path(args[0]));
            FileOutputFormat.setOutputPath(conf, new Path(args[1]));

            // apaga arquivos de entrada ou saida se ja existirem
            //conf.getWorkingDirectory().getFileSystem(conf).delete(new Path(args[0]), true);
            conf.getWorkingDirectory().getFileSystem(conf).delete(new Path(args[1]), true);

            long tempo_inicio = System.currentTimeMillis();

            // submete o job a execucao
            // bloqueia o programa ate que a execucao termine
            JobClient.runJob(conf).waitForCompletion();

            long tempo_fim = System.currentTimeMillis();

            long tempo_total = tempo_fim - tempo_inicio;
            System.err.println("-> Tempo total: " + tempo_total + " milisegundos.");

            return 0;
        }
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="Main">
     /** Recebe os nomes dos arquivos
      * @param args[0] nome do arquivo de entrada
      * @param args[1] nome do arquivo de saida
      * @throws IOException
      */
    public static void main(String[] args) throws IOException
    {
        new QuickSortHadoop().run(args);   
    }
    // </editor-fold>
}
