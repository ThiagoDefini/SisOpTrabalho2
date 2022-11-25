// PUCRS - Escola Politécnica - Sistemas Operacionais
// Prof. Fernando Dotti
// Código fornecido como parte da solução do projeto de Sistemas Operacionais
//
// VM
//    HW = memória, cpu
//    SW = tratamento int e chamada de sistema
// Funcionalidades de carga, execução e dump de memória

import java.util.*;
import java.util.concurrent.Semaphore;

public class Sistema {
	// -------------------------------------------------------------------------------------------------------
	// --------------------- H A R D W A R E - definicoes de HW ---------------------------------------------- 

	// -------------------------------------------------------------------------------------------------------
	// --------------------- M E M O R I A -  definicoes de palavra de memoria, memória ---------------------- 
	
	public class Memory {
		public int tamMem;    
        public Word[] m;                  // m representa a memória fisica:   um array de posicoes de memoria (word)
	
		public Memory(int size){
			tamMem = size;
		    m = new Word[tamMem];      
		    for (int i=0; i<tamMem; i++) { m[i] = new Word(Opcode.___,-1,-1,-1); };
		}
		
		public void dump(Word w) {        // funcoes de DUMP nao existem em hardware - colocadas aqui para facilidade
						System.out.print("[ "); 
						System.out.print(w.opc); System.out.print(", ");
						System.out.print(w.r1);  System.out.print(", ");
						System.out.print(w.r2);  System.out.print(", ");
						System.out.print(w.p);  System.out.println("  ] ");
		}
		public void dump(int ini, int fim) {
			for (int i = ini; i < fim; i++) {		
				System.out.print(i); System.out.print(":  ");  dump(m[i]);
			}
		}
    }
	
    // -------------------------------------------------------------------------------------------------------

	public class Word { 	// cada posicao da memoria tem uma instrucao (ou um dado)
		public Opcode opc; 	//
		public int r1; 		// indice do primeiro registrador da operacao (Rs ou Rd cfe opcode na tabela)
		public int r2; 		// indice do segundo registrador da operacao (Rc ou Rs cfe operacao)
		public int p; 		// parametro para instrucao (k ou A cfe operacao), ou o dado, se opcode = DADO

		public Word(Opcode _opc, int _r1, int _r2, int _p) {  // vide definição da VM - colunas vermelhas da tabela
			opc = _opc;   r1 = _r1;    r2 = _r2;	p = _p;
		}
	}
	
	// -------------------------------------------------------------------------------------------------------
    // --------------------- C P U  -  definicoes da CPU ----------------------------------------------------- 

	public enum Opcode {
		DATA, ___,		                    // se memoria nesta posicao tem um dado, usa DATA, se nao usada ee NULO ___
		JMP, JMPI, JMPIG, JMPIL, JMPIE,     // desvios e parada
		JMPIM, JMPIGM, JMPILM, JMPIEM, STOP, 
		JMPIGK, JMPILK, JMPIEK, JMPIGT,     
		ADDI, SUBI, ADD, SUB, MULT,         // matematicos
		LDI, LDD, STD, LDX, STX, MOVE,      // movimentacao
        TRAP,                               // chamada de sistema
		SHMALLOC, SHMREF
	}

	public enum Interrupts {               // possiveis interrupcoes que esta CPU gera
		noInterrupt, intEnderecoInvalido, intInstrucaoInvalida, intOverflow, intSTOP, intTimeOut, intBlocked;
	}

	public class CPU extends Thread{
		private int maxInt; // valores maximo e minimo para inteiros nesta cpu
		private int minInt;
							// característica do processador: contexto da CPU ...
		private int pc; 			// ... composto de program counter,
		private Word ir; 			// instruction register,
		private int[] reg;       	// registradores da CPU
		public Interrupts irpt; 	// durante instrucao, interrupcao pode ser sinalizada
		private int base;   		// base e limite de acesso na memoria
		private int limite; // por enquanto toda memoria pode ser acessada pelo processo rodando
							// ATE AQUI: contexto da CPU - tudo que precisa sobre o estado de um processo para executa-lo
							// nas proximas versoes isto pode modificar

		private Memory mem;               // mem tem funcoes de dump e o array m de memória 'fisica' 
		private Word[] m;                 // CPU acessa MEMORIA, guarda referencia a 'm'. m nao muda. semre será um array de palavras

		private InterruptHandling ih;     // significa desvio para rotinas de tratamento de  Int - se int ligada, desvia
        private SysCallHandling sysCall;  // significa desvio para tratamento de chamadas de sistema - trap 
		private boolean debug;            // se true entao mostra cada instrucao em execucao
		private Table table;
		private int timer;
		private int delta;
		boolean idle;
		
		public CPU(Memory _mem, InterruptHandling _ih, SysCallHandling _sysCall, boolean _debug) {     // ref a MEMORIA e interrupt handler passada na criacao da CPU
			maxInt =  32767;        // capacidade de representacao modelada
			minInt = -32767;        // se exceder deve gerar interrupcao de overflow
			mem = _mem;	            // usa mem para acessar funcoes auxiliares (dump)
			m = mem.m; 				// usa o atributo 'm' para acessar a memoria.
			reg = new int[10]; 		// aloca o espaço dos registradores - regs 8 e 9 usados somente para IO
			ih = _ih;               // aponta para rotinas de tratamento de int
            sysCall = _sysCall;     // aponta para rotinas de tratamento de chamadas de sistema
			debug =  _debug;        // se true, print da instrucao em execucao
			timer = 0;
			delta = 5;
			idle = true;
		}
		
		private boolean legal(int e) {                             // todo acesso a memoria tem que ser verificado
			for (int i = 0; i < table.page.length; i++) {
				if (memoryManager.getPhysicalAdress(e, table)/memoryManager.frameSize == table.page[i]) {
					return true;
				}
			}
			return false;
			// return true;
		}

		private boolean testOverflow(int v) {                       // toda operacao matematica deve avaliar se ocorre overflow                      
			if ((v < minInt) || (v > maxInt)) {                       
				irpt = Interrupts.intOverflow;             
				return false;
			};
			return true;
		}
		
		public void setContext(int _pc, Table _table) {  // no futuro esta funcao vai ter que ser
			idle = false;
			pc = _pc;                                              
			irpt = Interrupts.noInterrupt;                         
			table = _table;
		}
		
		public void run() { 		// execucao da CPU supoe que o contexto da CPU, vide acima, esta devidamente setado	
			while (true) {
				try {
					semaCPU.acquire();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				if (!idle) {
					while (true) { 			// ciclo de instrucoes. acaba cfe instrucao, veja cada caso.
					   // --------------------------------------------------------------------------------------------------
					   // FETCH
						if (legal(pc)) { 	// pc valido
							ir = m[memoryManager.getPhysicalAdress(pc, table)]; 	// <<<<<<<<<<<<           busca posicao da memoria apontada por pc, guarda em ir
							if (debug) { System.out.print("                               pc: "+memoryManager.getPhysicalAdress(pc, table)+"       exec: ");  mem.dump(ir); }
					   // --------------------------------------------------------------------------------------------------
					   // EXECUTA INSTRUCAO NO ir
							switch (ir.opc) {   // conforme o opcode (código de operação) executa
		
							// Instrucoes de Busca e Armazenamento em Memoria
								case LDI: // Rd <= k
									reg[ir.r1] = ir.p;
									pc++;
									break;
		
								case LDD: // Rd <= [A]
									if (legal(ir.p)) {
									   reg[ir.r1] = m[ir.p].p;
									   pc++;
									} else {
										irpt = Interrupts.intEnderecoInvalido;
									}
									break;
		
								case LDX: // RD <= [RS] // NOVA
									if (legal(reg[ir.r2])) {
										reg[ir.r1] = m[reg[ir.r2]].p;
										pc++;
									} else {
										irpt = Interrupts.intEnderecoInvalido;
									}
									break;
		
								case STD: // [A] <= Rs
									if (legal(ir.p)) {
										m[ir.p].opc = Opcode.DATA;
										m[ir.p].p = reg[ir.r1];
										pc++;
									} else {
										irpt = Interrupts.intEnderecoInvalido;
									}
									break;
		
								case STX: // [Rd] <= Rs
									if (legal(reg[ir.r1])) {
										m[reg[ir.r1]].opc = Opcode.DATA;      
										m[reg[ir.r1]].p = reg[ir.r2];          
										pc++;
									} else {
										irpt = Interrupts.intEnderecoInvalido;
									}
									break;
								
								case MOVE: // RD <= RS
									reg[ir.r1] = reg[ir.r2];
									pc++;
									break;	
									
							// Instrucoes Aritmeticas
								case ADD: // Rd <= Rd + Rs
									reg[ir.r1] = reg[ir.r1] + reg[ir.r2];
									testOverflow(reg[ir.r1]);
									pc++;
									break;
		
								case ADDI: // Rd <= Rd + k
									reg[ir.r1] = reg[ir.r1] + ir.p;
									testOverflow(reg[ir.r1]);
									pc++;
									break;
		
								case SUB: // Rd <= Rd - Rs
									reg[ir.r1] = reg[ir.r1] - reg[ir.r2];
									testOverflow(reg[ir.r1]);
									pc++;
									break;
		
								case SUBI: // RD <= RD - k // NOVA
									reg[ir.r1] = reg[ir.r1] - ir.p;
									testOverflow(reg[ir.r1]);
									pc++;
									break;
		
								case MULT: // Rd <= Rd * Rs
									reg[ir.r1] = reg[ir.r1] * reg[ir.r2];  
									testOverflow(reg[ir.r1]);
									pc++;
									break;
		
							// Instrucoes JUMP
								case JMP: // PC <= k
									pc = ir.p;
									break;
								
								case JMPIG: // If Rc > 0 Then PC <= Rs Else PC <= PC +1
									if (reg[ir.r2] > 0) {
										pc = reg[ir.r1];
									} else {
										pc++;
									}
									break;
		
								case JMPIGK: // If RC > 0 then PC <= k else PC++
									if (reg[ir.r2] > 0) {
										pc = ir.p;
									} else {
										pc++;
									}
									break;
			
								case JMPILK: // If RC < 0 then PC <= k else PC++
									 if (reg[ir.r2] < 0) {
										pc = ir.p;
									} else {
										pc++;
									}
									break;
			
								case JMPIEK: // If RC = 0 then PC <= k else PC++
										if (reg[ir.r2] == 0) {
											pc = ir.p;
										} else {
											pc++;
										}
									break;
			
			
								case JMPIL: // if Rc < 0 then PC <= Rs Else PC <= PC +1
										 if (reg[ir.r2] < 0) {
											pc = reg[ir.r1];
										} else {
											pc++;
										}
									break;
				
								case JMPIE: // If Rc = 0 Then PC <= Rs Else PC <= PC +1
										 if (reg[ir.r2] == 0) {
											pc = reg[ir.r1];
										} else {
											pc++;
										}
									break; 
			
								case JMPIM: // PC <= [A]
										 pc = m[ir.p].p;
									 break; 
			
								case JMPIGM: // If RC > 0 then PC <= [A] else PC++
										 if (reg[ir.r2] > 0) {
											pc = m[ir.p].p;
										} else {
											pc++;
										}
									 break;  
			
								case JMPILM: // If RC < 0 then PC <= k else PC++
										 if (reg[ir.r2] < 0) {
											pc = m[ir.p].p;
										} else {
											pc++;
										}
									 break; 
			
								case JMPIEM: // If RC = 0 then PC <= k else PC++
										if (reg[ir.r2] == 0) {
											pc = m[ir.p].p;
										} else {
											pc++;
										}
									 break; 
			
								case JMPIGT: // If RS>RC then PC <= k else PC++
										if (reg[ir.r1] > reg[ir.r2]) {
											pc = ir.p;
										} else {
											pc++;
										}
									 break; 
		
							// outras
								case STOP: // por enquanto, para execucao
									irpt = Interrupts.intSTOP;
									break;
		
								case DATA:
									irpt = Interrupts.intInstrucaoInvalida;
									break;
		
							// Chamada de sistema
								case TRAP:
									 sysCall.handle(table);            // <<<<< aqui desvia para rotina de chamada de sistema, no momento so temos IO
									 pc++;
									 break;
		
							// Inexistente
								default:
									irpt = Interrupts.intInstrucaoInvalida;
									break;
							}
						} else {
							irpt = Interrupts.intEnderecoInvalido;
						}
						timer++;
						if (irpt != null && timer % delta == 0) {
							irpt = Interrupts.intTimeOut;
						}
					   // --------------------------------------------------------------------------------------------------
					   // VERIFICA INTERRUPÇÃO !!! - TERCEIRA FASE DO CICLO DE INSTRUÇÕES
						if (!(irpt == Interrupts.noInterrupt)) {   // existe interrupção
							ih.handle(irpt,pc,reg);                       // desvia para rotina de tratamento
							break; // break sai do loop da cpu
						}
					}  // FIM DO CICLO DE UMA INSTRUÇÃO
				}
				idle = true;
				semaCPU.release();
			}		
		}      
	}
    // ------------------ C P U - fim ------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------

    
	
    // ------------------- V M  - constituida de CPU e MEMORIA -----------------------------------------------
    // -------------------------- atributos e construcao da VM -----------------------------------------------
	public class VM {
		public int tamMem;    
        public Word[] m;  
		public Memory mem;   
        public CPU cpu;    

        public VM(InterruptHandling ih, SysCallHandling sysCall){   
		 // vm deve ser configurada com endereço de tratamento de interrupcoes e de chamadas de sistema
	     // cria memória
		     tamMem = 1024;
  		 	 mem = new Memory(tamMem);
			 m = mem.m;
	  	 // cria cpu
			 cpu = new CPU(mem,ih,sysCall, true);                   // true liga debug
	    }	
	}
    // ------------------- V M  - fim ------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------

    // --------------------H A R D W A R E - fim -------------------------------------------------------------
    // -------------------------------------------------------------------------------------------------------

	// -------------------------------------------------------------------------------------------------------
	
	// -------------------------------------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------
	// ------------------- S O F T W A R E - inicio ----------------------------------------------------------

	// ------------------- I N T E R R U P C O E S  - rotinas de tratamento ----------------------------------
    public class InterruptHandling {
            public void handle(Interrupts irpt, int pc, int[] reg) {   // apenas avisa - todas interrupcoes neste momento finalizam o programa
				System.out.println("                                               Interrupcao "+ irpt+ "   pc: "+pc);
				switch (irpt) {
					case intEnderecoInvalido:
						processManager.deallocateProcess(processManager.ready.get(0).id);
						semaSch.release();
						break;
					case intOverflow:
						processManager.deallocateProcess(processManager.ready.get(0).id);
						semaSch.release();
						break;
					case intInstrucaoInvalida:
						processManager.deallocateProcess(processManager.ready.get(0).id);
						semaSch.release();
						break;
					case intSTOP:
						processManager.deallocateProcess(processManager.ready.get(0).id);
						semaSch.release();
						break;

					case intTimeOut:
						Sistema.ProcessManager.ProcessControlBlock toSave = processManager.ready.get(0);
						toSave.state.pointer = pc;
						toSave.state.registers = reg;
						processManager.ready.remove(0);
						processManager.ready.add(toSave);
						// exec(processManager.ready.get(0).state.pointer, processManager.ready.get(0).table);
						semaSch.release();
						break;

					case intBlocked:
						Sistema.ProcessManager.ProcessControlBlock toBeSaved = processManager.ready.get(0);
						toBeSaved.state.pointer = pc;
						toBeSaved.state.registers = reg;
						processManager.ready.remove(0);
						processManager.blocked.add(toBeSaved);
						semaSch.release();
						break;
				
					default:
					break;
				}
			}
	}

	// ------------------- E S C A L O N A D O R  - round-robin ----------------------

	public class Scheduler extends Thread{
		public void run(){
			while (true) {
				try {
					semaSch.acquire();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				if(!processManager.ready.isEmpty()){
					vm.cpu.setContext(processManager.ready.get(0).state.pointer, processManager.ready.get(0).table);
					semaCPU.release();
				}
			}
		}
	}

    // ------------------- C H A M A D A S  D E  S I S T E M A  - rotinas de tratamento ----------------------
    public class SysCallHandling {
        private VM vm;
        public void setVM(VM _vm){
            vm = _vm;
        }
        public void handle(Table table) {   // apenas avisa - todas interrupcoes neste momento finalizam o programa
            System.out.println("                                               Chamada de Sistema com op  /  par:  "+ vm.cpu.reg[8] + " / " + vm.cpu.reg[9]);
			if (vm.cpu.reg[8] == 1) {
				int position = vm.cpu.reg[9]; // contem endereço onde salvar a informação
				console.getInput(position); // requer para o console pedir uma entrada por parte do usuário
				vm.cpu.irpt = Interrupts.intBlocked;
				// int position = vm.cpu.reg[9];
				// vm.mem.m[position].p = input;
				

			}
			if (vm.cpu.reg[8] == 2) {
				System.out.println(vm.cpu.reg[9]);
			}
		}
    }

	// ------------------- G E R E N T E  D E  M E M O R I A  - paginação ----------------------
	public class MemoryManager {
		private int frameSize = 8; // number of instructions/words on a page
		private boolean[] frames;
		private int[] framesKeys;
		private Memory memory;

		public MemoryManager(Memory memory){
			this.memory = memory;
			int numberFrames = memory.tamMem / frameSize;
			frames = new boolean[numberFrames];
			framesKeys = new int[numberFrames];
		}

		public Table allocate(Table table, int key) {
			return getAvailableFrame(table, key);
		}

		public Table getNewPage(Table table, int key){			
			for (int i = 0; i < framesKeys.length; i++) {
				if (framesKeys[i] == key) {
					Table result = new Table(table, i);
					System.out.println("Shmref sucessfully");
					return result;
				}
			}
			System.out.println("the key was no found!");
			return table;
		}

		private Table getAvailableFrame(Table table, int key) {
			Table result = new Table(table);
			System.out.println("original number of pages: " + table.page.length);
			System.out.println("result number of pages: " + result.page.length);
			for (int i = 0; i < frames.length; i++) {
				if (frames[i] == false) {
					frames[i] = true;
					result.page[result.page.length - 1] = i;
					framesKeys[i] = key;
					System.out.println("Smalloc was successful!");
					return result;
				}
			}
			System.out.println("Smalloc was not possible!");
			return table;
		}

		public Table allocate(int numberWords) {
			int numberPages = numberWords / frameSize + 1;
			return getAvailableFrames(numberPages);
		}

		public void deallocate (Table table){
			for (int i = 0; i < table.page.length; i++) {
				frames[table.page[i]] = false;
				framesKeys[table.page[i]] = -1;
			}
		}

		public Table getAvailableFrames(int numberPages){
			Table result = new Table(numberPages);
			int counter = 0;
			for (int i = 0; i < frames.length; i++) {
				if (frames[i] == false) {
					frames[i] = true;
					result.page[counter] = i;
					if (counter >= numberPages - 1) {
						return result;	
					}					
					counter++;
				}
			}
			return null;
		}

		public int getPhysicalAdress(int logicalAddress, Table allocatedFrame){
			return logicToPhysicalAddressTranslator(logicalAddress, allocatedFrame);
		}

		public int logicToPhysicalAddressTranslator(int logicalAddress, Table allocatedFrame){
			if (logicalAddress/frameSize > allocatedFrame.page.length) {
				return Integer.MAX_VALUE;
			}	
			return (allocatedFrame.page[logicalAddress/frameSize]*frameSize)+(logicalAddress%frameSize);
			
		}		
	}

	public class Table{
		int[] page;
		int tableSize;

		public Table(int tableSize){
			page = new int[tableSize];
		}

		public Table(Table table){
			page = new int[table.page.length + 1];
			for (int i = 0; i < table.page.length; i++) {
				page[i] = table.page[i];
			}
		}

		public Table(Table table, int value){
			page = new int[table.page.length + 1];
			for (int i = 0; i < table.page.length; i++) {
				page[i] = table.page[i];
			}
			page[table.page.length - 1] = value;
		}
	}

	// ------------------- G E R E N T E  D E  P R O C E S S O S  - administra os processos ----------------------

	public class ProcessManager{
		private List<ProcessControlBlock> pcb;
		private List<ProcessControlBlock> ready;
		private List<ProcessControlBlock> blocked;
		private int id;
		
		public ProcessManager(){
			pcb = new ArrayList<>();
			ready = new ArrayList<>();
			blocked = new ArrayList<>();
			id = 0;
		}

		public boolean createProcess(Word[] program){
			Table processTable = memoryManager.allocate(program.length);
			if (processTable == null) {
				System.out.println("The process could not be created because there was no space in memory for this process");
				return false;
			}
			ProcessControlBlock createdPcb = new ProcessControlBlock();
			createdPcb.table = processTable;
			createdPcb.id = id;
			createdPcb.state = new State();
			id++;
			createdPcb.programLength = program.length;
			pcb.add(createdPcb);
			loadProgram(processTable, program, memoryManager.memory.m);
			ready.add(createdPcb);
			return true;
		}

		public boolean deallocateProcess(int id) {
			for (ProcessControlBlock processControlBlock : pcb) {
				if (processControlBlock.id == id) {
					memoryManager.deallocate(processControlBlock.table);
					pcb.remove(processControlBlock);
					ready.remove(processControlBlock);
					return true;
				}				
			}
			return false;
		}

		private class ProcessControlBlock {
			public int id;
			public Table table;
			public int programLength;
			public State state;
		}

		private class State{
			public int pointer;
			public int[] registers;

			public State(){
				pointer = 0;
				registers = null;
			}
		}
	}

    // ------------------ U T I L I T A R I O S   D O   S I S T E M A -----------------------------------------
	// ------------------ load é invocado a partir de requisição do usuário
	private void loadProgram(Table processTable, Word[] p, Word[] m) {
		for (int i = 0; i < p.length; i++) {
			m[memoryManager.getPhysicalAdress(i, processTable)].opc = p[i].opc;     m[memoryManager.getPhysicalAdress(i, processTable)].r1 = p[i].r1;     m[memoryManager.getPhysicalAdress(i, processTable)].r2 = p[i].r2;     m[memoryManager.getPhysicalAdress(i, processTable)].p = p[i].p;
		}
	}

	// private void loadProgram(Word[] p, Word[] m) {
	// 	for (int i = 0; i < p.length; i++) {
	// 		m[i].opc = p[i].opc;     m[i].r1 = p[i].r1;     m[i].r2 = p[i].r2;     m[i].p = p[i].p;
	// 	} 
	// }

	// private void loadProgram(Word[] p) {
	// 	loadProgram(p, vm.m);
	// }

	private void exec(int pointer, Table table){
				System.out.println("---------------------------------- programa carregado na memoria");			
		vm.cpu.setContext(pointer, table);      // seta estado da cpu ]
				System.out.println("---------------------------------- inicia execucao ");
		vm.cpu.run();                                // cpu roda programa ate parar	
				System.out.println("---------------------------------- memoria após execucao ");
	}

	// private void loadAndExec(Word[] p){
	// 	loadProgram(p);    // carga do programa na memoria
	// 			System.out.println("---------------------------------- programa carregado na memoria");
	// 			vm.mem.dump(0, p.length);            // dump da memoria nestas posicoes				
	// 	vm.cpu.setContext(0, vm.tamMem - 1, 0);      // seta estado da cpu ]
	// 			System.out.println("---------------------------------- inicia execucao ");
	// 	vm.cpu.run();                                // cpu roda programa ate parar	
	// 			System.out.println("---------------------------------- memoria após execucao ");
	// 			vm.mem.dump(0, p.length);            // dump da memoria com resultado
	// }

	// -------------------------------------------------------------------------------------------------------
    // -------------------  S I S T E M A --------------------------------------------------------------------

	public VM vm;
	public InterruptHandling ih;
	public SysCallHandling sysCall;
	public static Programas progs;
	public MemoryManager memoryManager;
	public ProcessManager processManager;
	public Shell shell;
	public Scheduler scheduler;
	public Console console;
	public Semaphore semaCPU;
	public Semaphore semaSch;

    public Sistema(){   // a VM com tratamento de interrupções
		 ih = new InterruptHandling();
         sysCall = new SysCallHandling();
		 vm = new VM(ih, sysCall);
		 sysCall.setVM(vm);
		 memoryManager = new MemoryManager(vm.mem);
		 processManager = new ProcessManager();
		 progs = new Programas();
		 shell = new Shell();
		 scheduler = new Scheduler();
		 console = new Console();
		 semaCPU = new Semaphore(1);
		 semaSch = new Semaphore(1);
	}

	public void run(){
		vm.cpu.start();
		scheduler.start();
		shell.start();
		console.start();
	}

    // -------------------  S I S T E M A - fim --------------------------------------------------------------
    // -------------------------------------------------------------------------------------------------------
	// -------------------  I N T E R F A C E - Menu --------------------------------------------------------------------
	public class Shell extends Thread{
		boolean canInput = false;
		public void run(){
			boolean programRunning = true;
			while (programRunning) {
				System.out.println("Escolha o numero abaixo:");
				System.out.println("1. Lista processos");
				System.out.println("2. Criar processo");
				System.out.println("3. Dump de PCB e pagina");
				System.out.println("4. Desaloca um processo");
				System.out.println("5. Dump de memoria de um inicio ate um fim, um valor entre 0 e 1024");
				System.out.println("6. Executa um processo");
				System.out.println("7. Liga/desliga trace");
				System.out.println("8. Sair");
				if (canInput) {
					System.out.println("9. Fornecer um valor de entrada");
				}
				Scanner in = new Scanner(System.in);
				int input = in.nextInt();
				switch (input) {
					case 1:
						System.out.println("IDs registrados no sistema:");
						for (int i = 0; i < processManager.pcb.size(); i++) {
							System.out.println(processManager.pcb.get(i).id);
						}
						break;
				
					case 2:
						System.out.println("Escolha qual processo abaixo quer iniciar:");
						System.out.println("1. Fatorial");
						System.out.println("2. ProgMinimo");
						System.out.println("3. Fibonacci10");
						System.out.println("4. FatorialTrap");
						System.out.println("5. FibonacciTrap");
						System.out.println("6. PB");
						System.out.println("7. PC");
						System.out.println("8. Voltar");
						int chosenProgram = in.nextInt();
						switch (chosenProgram) {
							case 1:
								if (processManager.createProcess(progs.fatorial)) {
									System.out.println("Processo criado com sucesso");
									break;
								}
								System.out.println("Nao foi possivel criar o processo");
								break;
						
							case 2:
								if (processManager.createProcess(progs.progMinimo)) {
									System.out.println("Processo criado com sucesso");
									break;
								}
								System.out.println("Nao foi possivel criar o processo");
								break;

							case 3:
								if (processManager.createProcess(progs.fibonacci10)) {
									System.out.println("Processo criado com sucesso");
									break;
								}
								System.out.println("Nao foi possivel criar o processo");
								break;

							case 4:
								if (processManager.createProcess(progs.fatorialTRAP)) {
									System.out.println("Processo criado com sucesso");
									break;
								}
								System.out.println("Nao foi possivel criar o processo");
								break;

							case 5:
								if (processManager.createProcess(progs.fibonacciTRAP)) {
									System.out.println("Processo criado com sucesso");
									break;
								}
								System.out.println("Nao foi possivel criar o processo");
								break;

							case 6:
								if (processManager.createProcess(progs.PB)) {
									System.out.println("Processo criado com sucesso");
									break;
								}
								System.out.println("Nao foi possivel criar o processo");
								break;

							case 7:
							if (processManager.createProcess(progs.PC)) {
								System.out.println("Processo criado com sucesso");
								break;
							}
							System.out.println("Nao foi possivel criar o processo");
								break;

							case 8:
								break;							
							
							default:
							System.out.println("Opcao nao reconhecida");
								break;
						}
						break;

					case 3:
						System.out.println("Informe o id do processo");
						int chosenId = in.nextInt();
						dump(chosenId);	
						break;					

					case 4:
						System.out.println("Informe o id do processo");
						chosenId = in.nextInt();
						deallocate(chosenId);
						break;

					case 5:
						System.out.println("Informe o inicio da memoria");
						int begin = in.nextInt();
						System.out.println("Informe o fim da memoria");
						int end = in.nextInt();
						dumpM(begin, end);
						break;

					case 6:
						System.out.println("Informe o id do processo");
						chosenId = in.nextInt();
						execute(chosenId);
						break;

					case 7:
						toggleTrace();
						break;

					case 8:
						System.exit(0);

					case 9:
						if (canInput) {
							System.out.println("Informe o valor de entrada");
							int inputValue = in.nextInt();
							console.order.get(0).saveInMemoryValue = inputValue;
							canInput = false;
						} else {
							System.out.println("Nao ha nenhuma requisicao de entrada");
						}
						break;

					default:
						System.out.println("Opcao incorreta, tente novamente");
						break;
				}
			}
		}

		public void dump(int id){
			System.out.println("Conteudo do PCB:");
			Table processTable = null;
			boolean found = false;
			for (int i = 0; i < processManager.pcb.size(); i++) {
				if (processManager.pcb.get(i).id == id) {
					found = true;
					System.out.println("Id:" + processManager.pcb.get(i).id);
					System.out.println("Quantidade de paginas: " + processManager.pcb.get(i).table.page.length);					
					processTable = processManager.pcb.get(i).table;
				}				
			}
			if (!found) {
				System.out.println("ID nao encontrado");
				return;
			}
			for (int i = 0; i < processTable.page.length; i++) {
				System.out.println("Page: " + i + "/Frame: " + processTable.page[i]);
				int processMemStart = processTable.page[i] * memoryManager.frameSize;
				int processMemEnd = processMemStart + memoryManager.frameSize;
				System.out.println("Conteudo do processo nas paginas");
				for (int j = processMemStart; j < processMemEnd; j++) {
					System.out.println("opc: " + vm.mem.m[j].opc + "/ ");
					System.out.println("p: " + vm.mem.m[j].p + "/ ");
					System.out.println("r1: " + vm.mem.m[j].r1 + "/ ");
					System.out.println("r2: " + vm.mem.m[j].r2 + "/ ");
				}
				processMemStart += memoryManager.frameSize-1;
				System.out.println();
			}
		}

		public void deallocate(int id){
			for (int i = 0; i < processManager.pcb.size(); i++) {
				if (processManager.pcb.get(i).id == id) {					
					if(processManager.deallocateProcess(id)){
						System.out.println("Processo de ID " + id + " desalocado");
					}else{
						System.out.println("Processo nao pode ser desalocado");
					}

				} else{
					System.out.println("ID nao encontrado");
				}
			}
		}

		public void dumpM(int begin, int end){
			if (begin < 0) {
				System.out.println("O comeco nao pode ser menor que zero");
			}
			if (end > 1024) {
				System.out.println("O fim nao pode ser maior que 1024");
			}
			System.out.println("Conteudo da memoria da posicao " + begin + " ate "+ end);
			for (int i = begin; i < end - 1; i++) {
				System.out.print("opc: " +  vm.mem.m[i].opc + "/ ");
				System.out.print("p: " + vm.mem.m[i].p + "/ ");
				System.out.print("r1: " + vm.mem.m[i].r1 + "/ ");
				System.out.print("r2: " + vm.mem.m[i].r2 + "/ ");
				System.out.println();
			}
		}

		public void execute(int id){
			for (int i = 0; i < processManager.pcb.size(); i++) {
				if (processManager.pcb.get(i).id == id) {					
					exec(0, processManager.pcb.get(i).table);
					deallocate(id);
				} else{
					System.out.println("ID nao encontrado");
				}
			}
			
		}

		public void toggleTrace(){
			if (vm.cpu.debug) {
				System.out.println("Trace desligado");
			}else{
				System.out.println("Trace ligado");
			}
			vm.cpu.debug = !vm.cpu.debug;
		}
		public void exit(){
			System.exit(0);
		}

	}
	//----------------- C O N S O L E --------------------
	public class Console extends Thread{
		public List<Order> order;

		public Console(){
			order = new ArrayList<>();
		}

		public void getInput(int position) {
			order.add(new Order(position));
			shell.canInput = true;
			
		}

		public void run(){
			while(true){
				if (order.size() > 0) {					
					if (order.get(0).saveInMemoryValue != -1) {
						vm.mem.m[order.get(0).memoryPosition].p = order.get(0).saveInMemoryValue;
					}
				}
			}
		}

		public class Order{
			public int memoryPosition;
			public int saveInMemoryValue;
			
			public Order(int memoryPos){
				this.memoryPosition = memoryPos;
				saveInMemoryValue = -1;
			}
		}

	}
	
    // -------------------------------------------------------------------------------------------------------
	// -------------------------------------------------------------------------------------------------------
    // ------------------- instancia e testa sistema
	public static void main(String args[]) {
		Sistema s = new Sistema();
		s.run();
		// s.processManager.createProcess(progs.progMinimo);
		//s.processManager.createProcess(progs.fatorialTRAP);
		//s.loadAndExec(progs.fibonacci10);
		//s.loadAndExec(progs.progMinimo);
		//s.loadAndExec(progs.fatorial);
		//s.loadAndExec(progs.fatorialTRAP); // saida
		//s.loadAndExec(progs.fibonacciTRAP); // entrada
		//s.loadAndExec(progs.PC); // bubble sort
			
	}


   // -------------------------------------------------------------------------------------------------------
   // -------------------------------------------------------------------------------------------------------
   // -------------------------------------------------------------------------------------------------------
   // --------------- P R O G R A M A S  - não fazem parte do sistema
   // esta classe representa programas armazenados (como se estivessem em disco) 
   // que podem ser carregados para a memória (load faz isto)

   public class Programas {
	   public Word[] fatorial = new Word[] {
	 	           // este fatorial so aceita valores positivos.   nao pode ser zero
	 											 // linha   coment
	 		new Word(Opcode.LDI, 0, -1, 4),      // 0   	r0 é valor a calcular fatorial
	 		new Word(Opcode.LDI, 1, -1, 1),      // 1   	r1 é 1 para multiplicar (por r0)
	 		new Word(Opcode.LDI, 6, -1, 1),      // 2   	r6 é 1 para ser o decremento
	 		new Word(Opcode.LDI, 7, -1, 8),      // 3   	r7 tem posicao de stop do programa = 8
	 		new Word(Opcode.JMPIE, 7, 0, 0),     // 4   	se r0=0 pula para r7(=8)
			new Word(Opcode.MULT, 1, 0, -1),     // 5   	r1 = r1 * r0
	 		new Word(Opcode.SUB, 0, 6, -1),      // 6   	decrementa r0 1 
	 		new Word(Opcode.JMP, -1, -1, 4),     // 7   	vai p posicao 4
	 		new Word(Opcode.STD, 1, -1, 10),     // 8   	coloca valor de r1 na posição 10
	 		new Word(Opcode.STOP, -1, -1, -1),   // 9   	stop
	 		new Word(Opcode.DATA, -1, -1, -1) }; // 10   ao final o valor do fatorial estará na posição 10 da memória                                    
		
	   public Word[] progMinimo = new Word[] {
		    new Word(Opcode.LDI, 0, -1, 999), 		
			new Word(Opcode.STD, 0, -1, 10), 
			new Word(Opcode.STD, 0, -1, 11), 
			new Word(Opcode.STD, 0, -1, 12), 
			new Word(Opcode.STD, 0, -1, 13), 
			new Word(Opcode.STD, 0, -1, 14), 
			new Word(Opcode.STOP, -1, -1, -1),//
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
		 };

	   public Word[] fibonacci10 = new Word[] { // mesmo que prog exemplo, so que usa r0 no lugar de r8
			new Word(Opcode.LDI, 1, -1, 0), 
			new Word(Opcode.STD, 1, -1, 20),   
			new Word(Opcode.LDI, 2, -1, 1),
			new Word(Opcode.STD, 2, -1, 21),  
			new Word(Opcode.LDI, 0, -1, 22),  
			new Word(Opcode.LDI, 6, -1, 6),
			new Word(Opcode.LDI, 7, -1, 31),  
			new Word(Opcode.LDI, 3, -1, 0), 
			new Word(Opcode.ADD, 3, 1, -1),
			new Word(Opcode.LDI, 1, -1, 0), 
			new Word(Opcode.ADD, 1, 2, -1), 
			new Word(Opcode.ADD, 2, 3, -1),
			new Word(Opcode.STX, 0, 2, -1), 
			new Word(Opcode.ADDI, 0, -1, 1), 
			new Word(Opcode.SUB, 7, 0, -1),
			new Word(Opcode.JMPIG, 6, 7, -1), 
			new Word(Opcode.STOP, -1, -1, -1), 
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),   // POS 20
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1) }; // ate aqui - serie de fibonacci ficara armazenada
		
       public Word[] fatorialTRAP = new Word[] {
		   new Word(Opcode.LDI, 0, -1, 7),// numero para colocar na memoria
		   new Word(Opcode.STD, 0, -1, 50),
		   new Word(Opcode.LDD, 0, -1, 50),
		   new Word(Opcode.LDI, 1, -1, -1),
		   new Word(Opcode.LDI, 2, -1, 13),// SALVAR POS STOP
           new Word(Opcode.JMPIL, 2, 0, -1),// caso negativo pula pro STD
           new Word(Opcode.LDI, 1, -1, 1),
           new Word(Opcode.LDI, 6, -1, 1),
           new Word(Opcode.LDI, 7, -1, 13),
           new Word(Opcode.JMPIE, 7, 0, 0), //POS 9 pula pra STD (Stop-1)
           new Word(Opcode.MULT, 1, 0, -1),
           new Word(Opcode.SUB, 0, 6, -1),
           new Word(Opcode.JMP, -1, -1, 9),// pula para o JMPIE
           new Word(Opcode.STD, 1, -1, 18),
           new Word(Opcode.LDI, 8, -1, 2),// escrita
           new Word(Opcode.LDI, 9, -1, 18),//endereco com valor a escrever
           new Word(Opcode.TRAP, -1, -1, -1),
           new Word(Opcode.STOP, -1, -1, -1), // POS 17
           new Word(Opcode.DATA, -1, -1, -1)  };//POS 18	
		   
	       public Word[] fibonacciTRAP = new Word[] { // mesmo que prog exemplo, so que usa r0 no lugar de r8
			new Word(Opcode.LDI, 8, -1, 1),// leitura
			new Word(Opcode.LDI, 9, -1, 100),//endereco a guardar
			new Word(Opcode.TRAP, -1, -1, -1),
			new Word(Opcode.LDD, 7, -1, 100),// numero do tamanho do fib
			new Word(Opcode.LDI, 3, -1, 0),
			new Word(Opcode.ADD, 3, 7, -1),
			new Word(Opcode.LDI, 4, -1, 36),//posicao para qual ira pular (stop) *
			new Word(Opcode.LDI, 1, -1, -1),// caso negativo
			new Word(Opcode.STD, 1, -1, 41),
			new Word(Opcode.JMPIL, 4, 7, -1),//pula pra stop caso negativo *
			new Word(Opcode.JMPIE, 4, 7, -1),//pula pra stop caso 0
			new Word(Opcode.ADDI, 7, -1, 41),// fibonacci + posição do stop
			new Word(Opcode.LDI, 1, -1, 0),
			new Word(Opcode.STD, 1, -1, 41),    // 25 posicao de memoria onde inicia a serie de fibonacci gerada
			new Word(Opcode.SUBI, 3, -1, 1),// se 1 pula pro stop
			new Word(Opcode.JMPIE, 4, 3, -1),
			new Word(Opcode.ADDI, 3, -1, 1),
			new Word(Opcode.LDI, 2, -1, 1),
			new Word(Opcode.STD, 2, -1, 42),
			new Word(Opcode.SUBI, 3, -1, 2),// se 2 pula pro stop
			new Word(Opcode.JMPIE, 4, 3, -1),
			new Word(Opcode.LDI, 0, -1, 43),
			new Word(Opcode.LDI, 6, -1, 25),// salva posição de retorno do loop
			new Word(Opcode.LDI, 5, -1, 0),//salva tamanho
			new Word(Opcode.ADD, 5, 7, -1),
			new Word(Opcode.LDI, 7, -1, 0),//zera (inicio do loop)
			new Word(Opcode.ADD, 7, 5, -1),//recarrega tamanho
			new Word(Opcode.LDI, 3, -1, 0),
			new Word(Opcode.ADD, 3, 1, -1),
			new Word(Opcode.LDI, 1, -1, 0),
			new Word(Opcode.ADD, 1, 2, -1),
			new Word(Opcode.ADD, 2, 3, -1),
			new Word(Opcode.STX, 0, 2, -1),
			new Word(Opcode.ADDI, 0, -1, 1),
			new Word(Opcode.SUB, 7, 0, -1),
			new Word(Opcode.JMPIG, 6, 7, -1),//volta para o inicio do loop
			new Word(Opcode.STOP, -1, -1, -1),   // POS 36
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),   // POS 41
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1),
			new Word(Opcode.DATA, -1, -1, -1)
	};

	public Word[] PB = new Word[] {
		//dado um inteiro em alguma posição de memória,
		// se for negativo armazena -1 na saída; se for positivo responde o fatorial do número na saída
		new Word(Opcode.LDI, 0, -1, 7),// numero para colocar na memoria
		new Word(Opcode.STD, 0, -1, 50),
		new Word(Opcode.LDD, 0, -1, 50),
		new Word(Opcode.LDI, 1, -1, -1),
		new Word(Opcode.LDI, 2, -1, 13),// SALVAR POS STOP
		new Word(Opcode.JMPIL, 2, 0, -1),// caso negativo pula pro STD
		new Word(Opcode.LDI, 1, -1, 1),
		new Word(Opcode.LDI, 6, -1, 1),
		new Word(Opcode.LDI, 7, -1, 13),
		new Word(Opcode.JMPIE, 7, 0, 0), //POS 9 pula pra STD (Stop-1)
		new Word(Opcode.MULT, 1, 0, -1),
		new Word(Opcode.SUB, 0, 6, -1),
		new Word(Opcode.JMP, -1, -1, 9),// pula para o JMPIE
		new Word(Opcode.STD, 1, -1, 15),
		new Word(Opcode.STOP, -1, -1, -1), // POS 14
		new Word(Opcode.DATA, -1, -1, -1)}; //POS 15

public Word[] PC = new Word[] {
		//Para um N definido (10 por exemplo)
		//o programa ordena um vetor de N números em alguma posição de memória;
		//ordena usando bubble sort
		//loop ate que não swap nada
		//passando pelos N valores
		//faz swap de vizinhos se da esquerda maior que da direita
		new Word(Opcode.LDI, 7, -1, 5),// TAMANHO DO BUBBLE SORT (N)
		new Word(Opcode.LDI, 6, -1, 5),//aux N
		new Word(Opcode.LDI, 5, -1, 46),//LOCAL DA MEMORIA
		new Word(Opcode.LDI, 4, -1, 47),//aux local memoria
		new Word(Opcode.LDI, 0, -1, 4),//colocando valores na memoria
		new Word(Opcode.STD, 0, -1, 46),
		new Word(Opcode.LDI, 0, -1, 3),
		new Word(Opcode.STD, 0, -1, 47),
		new Word(Opcode.LDI, 0, -1, 5),
		new Word(Opcode.STD, 0, -1, 48),
		new Word(Opcode.LDI, 0, -1, 1),
		new Word(Opcode.STD, 0, -1, 49),
		new Word(Opcode.LDI, 0, -1, 2),
		new Word(Opcode.STD, 0, -1, 50),//colocando valores na memoria até aqui - POS 13
		new Word(Opcode.LDI, 3, -1, 25),// Posicao para pulo CHAVE 1
		new Word(Opcode.STD, 3, -1, 99),
		new Word(Opcode.LDI, 3, -1, 22),// Posicao para pulo CHAVE 2
		new Word(Opcode.STD, 3, -1, 98),
		new Word(Opcode.LDI, 3, -1, 38),// Posicao para pulo CHAVE 3
		new Word(Opcode.STD, 3, -1, 97),
		new Word(Opcode.LDI, 3, -1, 25),// Posicao para pulo CHAVE 4 (não usada)
		new Word(Opcode.STD, 3, -1, 96),
		new Word(Opcode.LDI, 6, -1, 0),//r6 = r7 - 1 POS 22
		new Word(Opcode.ADD, 6, 7, -1),
		new Word(Opcode.SUBI, 6, -1, 1),//ate aqui
		new Word(Opcode.JMPIEM, -1, 6, 97),//CHAVE 3 para pular quando r7 for 1 e r6 0 para interomper o loop de vez do programa
		new Word(Opcode.LDX, 0, 5, -1),//r0 e r1 pegando valores das posições da memoria POS 26
		new Word(Opcode.LDX, 1, 4, -1),
		new Word(Opcode.LDI, 2, -1, 0),
		new Word(Opcode.ADD, 2, 0, -1),
		new Word(Opcode.SUB, 2, 1, -1),
		new Word(Opcode.ADDI, 4, -1, 1),
		new Word(Opcode.SUBI, 6, -1, 1),
		new Word(Opcode.JMPILM, -1, 2, 99),//LOOP chave 1 caso neg procura prox
		new Word(Opcode.STX, 5, 1, -1),
		new Word(Opcode.SUBI, 4, -1, 1),
		new Word(Opcode.STX, 4, 0, -1),
		new Word(Opcode.ADDI, 4, -1, 1),
		new Word(Opcode.JMPIGM, -1, 6, 99),//LOOP chave 1 POS 38
		new Word(Opcode.ADDI, 5, -1, 1),
		new Word(Opcode.SUBI, 7, -1, 1),
		new Word(Opcode.LDI, 4, -1, 0),//r4 = r5 + 1 POS 41
		new Word(Opcode.ADD, 4, 5, -1),
		new Word(Opcode.ADDI, 4, -1, 1),//ate aqui
		new Word(Opcode.JMPIGM, -1, 7, 98),//LOOP chave 2
		new Word(Opcode.STOP, -1, -1, -1), // POS 45
		new Word(Opcode.DATA, -1, -1, -1),
		new Word(Opcode.DATA, -1, -1, -1),
		new Word(Opcode.DATA, -1, -1, -1),
		new Word(Opcode.DATA, -1, -1, -1),
		new Word(Opcode.DATA, -1, -1, -1),
		new Word(Opcode.DATA, -1, -1, -1),
		new Word(Opcode.DATA, -1, -1, -1),
		new Word(Opcode.DATA, -1, -1, -1)};
   }
}

