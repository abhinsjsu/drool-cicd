# Copyright 2000 Brad Chapman.  All rights reserved.
#
# This file is part of the Biopython distribution and governed by your
# choice of the "Biopython License Agreement" or the "BSD 3-Clause License".
# Please see the LICENSE file that should have been included as part of this
# package.
"""Extract information from alignment objects.
In order to try and avoid huge alignment objects with tons of functions,
functions which return summary type information about alignments should
be put into classes in this module.
"""


import math
import sys

from Bio.Seq import Seq


class SummaryInfo:
    """Calculate summary info about the alignment.
    This class should be used to calculate information summarizing the
    results of an alignment. This may either be straight consensus info
    or more complicated things.
    """

    def __init__(self, alignment):
        """Initialize with the alignment to calculate information on.
        ic_vector attribute. A list of ic content for each column number.
        """
        self.alignment = alignment
        self.ic_vector = []

    def dumb_consensus(self, threshold=0.7, ambiguous="X", require_multiple=False):
        """Output a fast consensus sequence of the alignment.
        This doesn't do anything fancy at all. It will just go through the
        sequence residue by residue and count up the number of each type
        of residue (ie. A or G or T or C for DNA) in all sequences in the
        alignment. If the percentage of the most common residue type is
        greater then the passed threshold, then we will add that residue type,
        otherwise an ambiguous character will be added.
        This could be made a lot fancier (ie. to take a substitution matrix
        into account), but it just meant for a quick and dirty consensus.
        Arguments:
         - threshold - The threshold value that is required to add a particular
           atom.
         - ambiguous - The ambiguous character to be added when the threshold is
           not reached.
         - require_multiple - If set as True, this will require that more than
           1 sequence be part of an alignment to put it in the consensus (ie.
           not just 1 sequence and gaps).
        """
        # Iddo Friedberg, 1-JUL-2004: changed ambiguous default to "X"
        consensus = ""

        # find the length of the consensus we are creating
        con_len = self.alignment.get_alignment_length()

        # go through each seq item
        for n in range(con_len):
            # keep track of the counts of the different atoms we get
            atom_dict = {}
            num_atoms = 0

            for record in self.alignment:
                # make sure we haven't run past the end of any sequences
                # if they are of different lengths
                if n < len(record.seq):
                    if record.seq[n] != "-" and record.seq[n] != ".":
                        if record.seq[n] not in atom_dict:
                            atom_dict[record.seq[n]] = 1
                        else:
                            atom_dict[record.seq[n]] += 1

                        num_atoms = num_atoms + 1

            max_atoms = []
            max_size = 0

            for atom in atom_dict:
                if atom_dict[atom] > max_size:
                    max_atoms = [atom]
                    max_size = atom_dict[atom]
                elif atom_dict[atom] == max_size:
                    max_atoms.append(atom)

            if require_multiple and num_atoms == 1:
                consensus += ambiguous
            elif (len(max_atoms) == 1) and (
                    (float(max_size) / float(num_atoms)) >= threshold
            ):
                consensus += max_atoms[0]
            else:
                consensus += ambiguous

        return Seq(consensus)

    def gap_consensus(self, threshold=0.7, ambiguous="X", require_multiple=False):
        """Output a fast consensus sequence of the alignment, allowing gaps.
        Same as dumb_consensus(), but allows gap on the output.
        Things to do:
         - Let the user define that with only one gap, the result
           character in consensus is gap.
         - Let the user select gap character, now
           it takes the same as input.
        """
        consensus = ""

        # find the length of the consensus we are creating
        con_len = self.alignment.get_alignment_length()

        # go through each seq item
        for n in range(con_len):
            # keep track of the counts of the different atoms we get
            atom_dict = {}
            num_atoms = 0

            for record in self.alignment:
                # make sure we haven't run past the end of any sequences
                # if they are of different lengths
                if n < len(record.seq):
                    if record.seq[n] not in atom_dict:
                        atom_dict[record.seq[n]] = 1
                    else:
                        atom_dict[record.seq[n]] += 1

                    num_atoms += 1

            max_atoms = []
            max_size = 0

            for atom in atom_dict:
                if atom_dict[atom] > max_size:
                    max_atoms = [atom]
                    max_size = atom_dict[atom]
                elif atom_dict[atom] == max_size:
                    max_atoms.append(atom)

            if require_multiple and num_atoms == 1:
                consensus += ambiguous
            elif (len(max_atoms) == 1) and (
                    (float(max_size) / float(num_atoms)) >= threshold
            ):
                consensus += max_atoms[0]
            else:
                consensus += ambiguous

        return Seq(consensus)